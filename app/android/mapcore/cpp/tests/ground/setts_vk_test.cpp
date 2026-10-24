// A sett road's courses on the host's Vulkan (ENGINE.md step 4, "Trees and things"): the ground pass draws the bundled
// Westerkerk's sett roads alone (Ground::draw_only_setts) over the frame's clear colour, far along them, nearer and at
// their sharpest bends, at rest, and every pixel is the exact layer of each road's lines (amsterdam.py's one path a
// road at opacity .6: inside it the lines over each other, the path over the ground), their ends as the vertex stage
// projects and snaps them (camera.slang's text as C++), to the blending's rounding: the GPU blends each line in the
// attachment's precision, the model in double. The twin emits a path for every road drawn.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "geometry/Ground.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/tiles/support.hpp"
#include "twin/Twin.hpp"
#include "twin/ground/LineShader.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <format>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

using namespace atlas;

namespace {

namespace geo = atlas::geometry;
namespace pm = atlas::pymath;
namespace sh = atlas::shaders;

/// A camera of the map (buildings_vk_test.cpp's).
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance,
                                        std::uint32_t w, std::uint32_t h, double d) {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.latitude = lat;
    c.longitude = lon;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.density = d;
    c.width_px = w;
    c.height_px = h;
    c.width_dp = static_cast<std::uint32_t>(std::lround(w / d));
    c.height_dp = static_cast<std::uint32_t>(std::lround(h / d));
    c.principal_x = c.width_dp / 2.0;
    c.principal_y = c.height_dp * 0.48;
    c.flags = style::camera_flag::engine_mode | style::camera_flag::rest;
    return c;
}

/// A sett road of the bundled pack: its lines' ends (frame metres) and its sharpest bend's line.
struct Road {
    std::vector<geo::P2> ends;
    std::size_t bend = 0;
};

/// The pack's sett roads at its first level, the longest first.
std::vector<Road> roads_of(const tiles::Pack& pack) {
    const tiles::RenderHead& head = *pack.head();
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "setts vk test");
    std::vector<Road> out;
    for (const tiles::ChunkRef& c : pack.chunks().level(head.levels.front())) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        REQUIRE(tile.has_value());
        const geo::P2 origin{.x = head.frame.x(tile->header.west), .z = head.frame.z(tile->header.south)};
        const geo::GroundTile g = geo::build_ground(*tile, head, *pack.style(), 7, origin);
        for (const geo::Stroke& s : g.strokes) {
            if (s.kind != geo::StrokeKind::setts) continue;
            const geo::Polyline& pl = g.polylines[s.first];
            Road r;
            r.ends.assign(g.points.begin() + pl.first, g.points.begin() + pl.first + pl.count);
            const auto first = g.reaches.begin() + pl.reach;
            r.bend = static_cast<std::size_t>(
                std::ranges::max_element(first, first + (pl.count / 2), {}, [](std::uint32_t w) { return w & 63U; }) -
                first);
            out.push_back(std::move(r));
        }
    }
    std::ranges::sort(out, [](const Road& a, const Road& b) { return a.ends.size() > b.ends.size(); });
    return out;
}

/// A drawn sett road as the vertex stage places it: its lines' ends in device px, its colour and opacity.
struct Path {
    std::vector<sh::SettLine> lines;
    std::array<double, 3> colour{};
    double op = 0;
    double hw = 0;
};

/// The frame's drawn sett roads, each line's ends as stroke_vs projects them (snapped at rest).
[[nodiscard]] std::vector<Path> paths_of(const passes::GroundFrame& f, const frame::FrameState& state) {
    std::vector<Path> out;
    const shaders::FrameCamera& cam = state.block.camera;
    for (const passes::DrawnTile& t : f.tiles) {
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
            const geo::Stroke& s = g.strokes[i];
            if (s.kind != geo::StrokeKind::setts || f.stroke_drawn[t.strokes + i] == 0) continue;
            const geo::Polyline& pl = g.polylines[s.first];
            Path p;
            p.colour = {static_cast<double>(s.paint.day.r), static_cast<double>(s.paint.day.g),
                        static_cast<double>(s.paint.day.b)};
            p.op = static_cast<double>(s.paint.day.a) / 255.0;
            p.hw = f.stroke_width[t.strokes + i] * static_cast<double>(cam.frame.z) / 2;
            for (std::uint32_t k = 0; k + 1 < pl.count; k += 2) {
                auto at = [&](std::uint32_t q) {
                    const geo::P2 w = g.points[pl.first + q];
                    const sh::float3 local{static_cast<float>(w.x - g.origin.x), static_cast<float>(pl.y),
                                           static_cast<float>(w.z - g.origin.z)};
                    return sh::camera_of(cam, sh::eye_relative(t.place, local));
                };
                const sh::float3 ka = at(k);
                const sh::float3 kb = at(k + 1);
                sh::SettLine l{};
                l.drawn = ka.z >= cam.frame.w && kb.z >= cam.frame.w;
                l.a = sh::project_css(cam, ka) * cam.frame.z;
                l.b = sh::project_css(cam, kb) * cam.frame.z;
                if (l.drawn) p.lines.push_back(l);
            }
            out.push_back(std::move(p));
        }
    }
    return out;
}

/// How a frame's pixels compare with the exact layer of its sett roads over the clear colour.
struct Compared {
    std::size_t paths = 0;
    std::size_t lines = 0;
    std::size_t covered = 0; ///< pixels a line covers
    std::size_t outside = 0; ///< pixels no line covers that are not the clear colour
    std::size_t off = 0;     ///< covered pixels more than 3 levels from the layer in a channel
    int worst = 0;           ///< the most levels a covered pixel's channel is from the layer
    double mean = 0;         ///< the mean of the covered pixels' largest channel differences
    double darkest = 1;      ///< the least of the layers' factor (1 - op · A) over the covered pixels
    std::string first;
};

[[nodiscard]] Compared compare(const gpu::Pixels& px, std::span<const Path> paths) {
    Compared r;
    const auto w = static_cast<std::int64_t>(px.width);
    const auto h = static_cast<std::int64_t>(px.height);
    auto at = [&](std::int64_t x, std::int64_t y, int c) {
        return static_cast<int>(px.rgba[(static_cast<std::size_t>((y * w) + x) * 4) + static_cast<std::size_t>(c)]);
    };
    // the background: the clear colour, as the frame's first pixel holds it (no line reaches the corner)
    const std::array<int, 3> bg{at(0, 0, 0), at(0, 0, 1), at(0, 0, 2)};
    std::unordered_map<std::int64_t, double> factor; // per covered pixel: the product of every path's (1 - op · A)
    std::array<double, 3> colour{};
    for (const Path& path : paths) {
        ++r.paths;
        colour = path.colour;
        const auto hw = static_cast<float>(path.hw);
        const float wide = 2 * hw;
        const sh::uint mode = wide <= 1.0F ? sh::LINE_HAIR : sh::LINE_AREA;
        REQUIRE(wide < 2.0F);
        std::unordered_map<std::int64_t, double> through; // per pixel: the product of its lines' (1 - a)
        for (const sh::SettLine& l : path.lines) {
            ++r.lines;
            const float margin = hw + 2.0F;
            const auto x0 =
                std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(l.a.x, l.b.x) - margin)));
            const auto x1 =
                std::min<std::int64_t>(w - 1, static_cast<std::int64_t>(std::floor(std::max(l.a.x, l.b.x) + margin)));
            const auto y0 =
                std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(std::min(l.a.y, l.b.y) - margin)));
            const auto y1 =
                std::min<std::int64_t>(h - 1, static_cast<std::int64_t>(std::floor(std::max(l.a.y, l.b.y) + margin)));
            for (std::int64_t y = y0; y <= y1; ++y) {
                for (std::int64_t x = x0; x <= x1; ++x) {
                    const sh::float2 pixel{static_cast<float>(x), static_cast<float>(y)};
                    const float a = sh::min(sh::sett_cover(l, hw, mode, pixel, pixel + 0.5F), 1.0F);
                    if (a <= 0.0F) continue;
                    auto [it, fresh] = through.try_emplace((y * w) + x, 1.0);
                    (void)fresh;
                    it->second *= 1.0 - static_cast<double>(a);
                }
            }
        }
        for (const auto& [p, t] : through) {
            auto [it, fresh] = factor.try_emplace(p, 1.0);
            (void)fresh;
            it->second *= 1.0 - (path.op * (1.0 - t));
        }
    }
    double sum = 0;
    for (std::int64_t y = 0; y < h; ++y) {
        for (std::int64_t x = 0; x < w; ++x) {
            const auto it = factor.find((y * w) + x);
            if (it == factor.end()) {
                if (at(x, y, 0) != bg[0] || at(x, y, 1) != bg[1] || at(x, y, 2) != bg[2]) ++r.outside;
                continue;
            }
            ++r.covered;
            r.darkest = std::min(r.darkest, it->second);
            int most = 0;
            for (int c = 0; c < 3; ++c) {
                const double want =
                    colour.at(static_cast<std::size_t>(c)) +
                    ((bg.at(static_cast<std::size_t>(c)) - colour.at(static_cast<std::size_t>(c))) * it->second);
                most = std::max(most, static_cast<int>(std::lround(std::abs(at(x, y, c) - want))));
            }
            sum += most;
            r.worst = std::max(r.worst, most);
            if (most > 3) {
                if (r.off == 0)
                    r.first = std::format("({}, {}): {} levels, the layer's factor {:.4f}", x, y, most, it->second);
                ++r.off;
            }
        }
    }
    r.mean = r.covered > 0 ? sum / static_cast<double>(r.covered) : 0;
    return r;
}

} // namespace

TEST_CASE(
    "setts: the bundled sett roads drawn alone on the host's Vulkan, each road one layer, as their twin's paths") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    const std::filesystem::path path = std::filesystem::path(dir) / "amsterdam.atlas";
    if (dir.empty() || !std::filesystem::exists(path)) {
        MESSAGE("ATLAS_TILES_DIR has no amsterdam.atlas: the sett roads are not drawn here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    auto pack = test::open_pack(path.string());
    ATLAS_REQUIRE(pack.has_value(), "the pack opens");
    const std::vector<Road> roads = roads_of(*pack);
    ATLAS_REQUIRE(roads.size() >= 2, "the bundled Westerkerk has sett roads");
    const auto ladder = view::Ladder::of(*pack->head());
    ATLAS_REQUIRE(ladder.has_value());
    const view::GroundFrame& gf = ladder->frame();
    std::size_t covered = 0;
    double darkest = 1;
    for (std::size_t k = 0; k < 2; ++k) {
        const Road& road = roads[k];
        const std::size_t n = road.ends.size() / 2;
        auto middle = [&](std::size_t j) {
            return geo::P2{.x = (road.ends[2 * j].x + road.ends[(2 * j) + 1].x) / 2,
                           .z = (road.ends[2 * j].z + road.ends[(2 * j) + 1].z) / 2};
        };
        const geo::P2 a = middle(n / 4);
        const geo::P2 b = middle((3 * n) / 4);
        const geo::P2 mid = middle(n / 2);
        // the port's yaw along the road (Camera: 0 looks toward -z), as the map's bearing
        const double along = view::bearing_of(pm::degrees(pm::atan2(b.x - a.x, -(b.z - a.z))));
        struct Shot {
            geo::P2 target;
            double bearing = 0;
            double pitch = 0; ///< the port's: from the horizon
            double dist = 0;
            std::uint32_t w = 0;
            std::uint32_t h = 0;
            double density = 1;
            const char* name = "";
        };
        const std::array<Shot, 4> shots{
            Shot{.target = mid,
                 .bearing = along,
                 .pitch = 10.5,
                 .dist = 450,
                 .w = 412,
                 .h = 916,
                 .density = 1.0,
                 .name = "far along it"},
            Shot{.target = mid,
                 .bearing = along,
                 .pitch = 10.5,
                 .dist = 450,
                 .w = 1080,
                 .h = 2400,
                 .density = 1080.0 / 412.0,
                 .name = "far along it on S1"},
            Shot{.target = mid,
                 .bearing = along,
                 .pitch = 30,
                 .dist = 250,
                 .w = 1080,
                 .h = 2400,
                 .density = 1080.0 / 412.0,
                 .name = "along it"},
            Shot{.target = middle(road.bend),
                 .bearing = along + 45,
                 .pitch = 55,
                 .dist = 25,
                 .w = 1080,
                 .h = 2400,
                 .density = 1080.0 / 412.0,
                 .name = "at its sharpest bend"},
        };
        for (const Shot& s : shots) {
            const double lat = gf.lat0 - (s.target.z / gf.kz);
            const double lon = gf.lon0 + (s.target.x / gf.kx);
            const style::CameraState cam = camera(lat, lon, s.bearing, 90.0 - s.pitch, s.dist, s.w, s.h, s.density);
            twin::Stream stream;
            std::vector<Path> paths;
            const auto drawn = test::draw_ground(
                *session, cam, test::sunny(), dir, "amsterdam",
                [&](passes::Ground& g, const frame::FrameState& state) {
                    g.emit(state, stream, passes::Ground::phase_opaque);
                    paths = paths_of(g.decided(), state);
                },
                12, [](passes::Ground& g) { g.draw_only_setts(true); });
            ATLAS_REQUIRE(drawn.has_value(), "the frame draws");
            const twin::Decoded decoded = twin::decode(stream.bytes());
            ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
            // the twin's paths: one a road with a line drawn, at the path's opacity
            const auto emitted = std::ranges::count_if(decoded.document.elements, [](const twin::Element& e) {
                return e.kind == twin::Op::path && e.node.opacity < 1.0F;
            });
            CHECK(emitted == std::ranges::count_if(paths, [](const Path& p) { return !p.lines.empty(); }));
            const Compared r = compare(drawn->pixels, paths);
            MESSAGE(std::format("road {} ({} lines) {}: {} paths, {} lines drawn, {} pixels covered, the layers' least "
                                "factor {:.3f}; {} pixels more than 3 levels off (the most {}, the mean {:.3f}), {} "
                                "uncovered pixels not the clear colour",
                                k, n, s.name, r.paths, r.lines, r.covered, r.darkest, r.off, r.worst, r.mean,
                                r.outside));
            if (const std::string out = test::environment("ATLAS_SETTS_PPM"); !out.empty()) {
                test::write_ppm(drawn->pixels, std::format("{}/setts-{}-{}.ppm", out, k, s.name));
            }
            CHECK(r.paths > 0);
            CHECK_MESSAGE(r.off == 0, r.first);
            CHECK(r.outside == 0);
            covered += r.covered;
            darkest = std::min(darkest, r.darkest);
            CHECK(session->take_messages().empty());
        }
    }
    CHECK(covered > 1000);
    // the far views' crowded lines: their layer near its whole opacity (1 - .6), as the renders' path reaches it
    CHECK(darkest < 0.45);
}

#endif
