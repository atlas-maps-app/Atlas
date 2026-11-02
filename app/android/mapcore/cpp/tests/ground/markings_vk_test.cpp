// The app's map's painted lines on the host's Vulkan (passes/Lines.cpp's runs in metres, shaders/ground/ground.slang's
// stroke_vs): a window's taxiway and a big road, each of five points whose middle one lies behind a low camera looking
// north along it (the user, 2 November: the yellow at Schiphol "glitches out and covers and takes over much more area",
// and a road's lines at 52.69297, 4.77424). The renders' one width at the middle point is w · focal there, the whole
// line some 400 CSS px wide; on the ground each point is px at its own depth (render/regions.py's px a point at a
// time). Drawn by the ground alone with its lines and without them, every pixel the lines change lies within its line's
// own width there and a pixel and a half beyond, and the lines reach the frame's bottom edge, their segments through
// the near plane kept up to it rather than folded away. From higher up, every point ahead, the lines thin from 8 device
// px to under one, still within their widths, the taxiway's each row as much ink as it is wide there (each pixel
// covered as a line of its width there is: a hairline, the exact area or the samples).
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Window.hpp"
#include "passes/Ground.hpp"
#include "scene/Cover.hpp"
#include "scene/World.hpp"
#include "tests/base/require.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tiles/Grid.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <fstream>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

namespace geo = atlas::geometry;

/// The window's tile: the Westerkerk's.
const tiles::TileKey window_tile = tiles::tile_of(14, 523'746'000, 48'840'000);

/// The latitudes of the lines' five points, 332 m apart; the middle one, the renders' point for their width.
constexpr std::array<double, 5> lats{52.3630, 52.3660, 52.3690, 52.3720, 52.3750};
constexpr double taxiway_lon = 4.8950;
constexpr double road_lon = 4.8830;

/// A window with a taxiway and a primary road of two lanes along the latitudes, 815 m apart; its pack written to the
/// tests' scratch folder.
[[nodiscard]] std::string markings_window() {
    std::string taxiway;
    std::string road;
    for (const double lat : lats) {
        taxiway += std::format(R"({}{{"lat": {}, "lon": {}}})", taxiway.empty() ? "" : ", ", lat, taxiway_lon);
        road += std::format(R"({}{{"lat": {}, "lon": {}}})", road.empty() ? "" : ", ", lat, road_lon);
    }
    const std::string data =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-11-02T08:00:00Z"}, "elements": [)"
        R"({"type": "way", "id": 31, "tags": {"aeroway": "taxiway"}, "geometry": [)" +
        taxiway + R"(]}, {"type": "way", "id": 40, "tags": {"highway": "primary", "lanes": "2"}, "geometry": [)" +
        road + "]}]}";
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), window_tile,
                                          load::place_of(window_tile));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "markings-vk";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A segment of a line as it lies on the screen: its ends (device px) and its half widths there.
struct Piece {
    std::array<double, 2> a{};
    std::array<double, 2> b{};
    double ha = 0;
    double hb = 0;
};

/// What a frame of the window drew: its pixels and, of its lines of `kinds` (the drawn ones), each segment's part ahead
/// of the near plane as the ground lays it, px at each end's own depth; how deep the first's width point lay, and its
/// paint by day.
struct WindowDraw {
    gpu::Pixels pixels;
    std::vector<Piece> pieces;
    std::size_t lines = 0;
    double at_depth = 0;
    geo::Rgba paint;
};

/// The window drawn by the ground alone at `camera` in the app's map, until its tile is built and faded in.
std::optional<WindowDraw> draw_window(const test::Session& session, const std::string& path,
                                      const style::CameraState& camera, std::span<const geo::StrokeKind> kinds) {
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    if (!cache) return std::nullopt;
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = test::ground_of(passes);
    auto builder =
        frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0, .grain = false, .grounded = true});
    if (!builder) return std::nullopt;
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    if (!world) return std::nullopt;
    const std::string id = "window/" + load::place_of(window_tile).name;
    if (!(*world)->store().add({.id = id, .kind = tiles::PackKind::window, .path = path})) return std::nullopt;
    auto output = gpu::Offscreen::create(device, {.width = camera.width_px, .height = camera.height_px}, 2);
    if (!output) return std::nullopt;
    const style::ThemeBlock theme = test::sunny();
    (*builder)->set_inputs(
        {.camera = camera, .theme = theme, .seconds = 0, .mode = frame::Mode::structural, .scene = world->get()});
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    if (!renderer) return std::nullopt;
    bool settled = false;
    for (int f = 0; f < 61 && !settled; ++f) {
        now = static_cast<double>(f) * 0.25;
        (*builder)->set_inputs(
            {.camera = camera, .theme = theme, .seconds = now, .mode = frame::Mode::structural, .scene = world->get()});
        if (!(*renderer)->draw(0)) return std::nullopt;
        (*renderer)->drain();
        (*world)->residency().wait_idle();
        const scene::Cover& cover = *(*world)->cover();
        settled = f > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                  std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    // one more frame, all of it drawn
    if (!(*renderer)->draw(0)) return std::nullopt;
    (*renderer)->drain();
    auto packed = (*output)->read_packed((*output)->latest());
    if (!packed) return std::nullopt;
    WindowDraw d{.pixels = gpu::to_rgba8(*packed), .pieces = {}, .lines = 0, .at_depth = 0, .paint = {}};
    const frame::FrameState& state = (*builder)->state();
    CHECK(state.grounded);
    const auto density = static_cast<double>(state.block.camera.frame.z);
    const passes::GroundFrame& decided = g->decided();
    bool first = true;
    for (const passes::DrawnTile& t : decided.tiles) {
        const view::Camera& cam = *t.cam;
        const geo::GroundTile& ground = *t.ground;
        for (std::uint32_t i = 0; i < ground.strokes.size(); ++i) {
            const geo::Stroke& s = ground.strokes[i];
            if (std::ranges::find(kinds, s.kind) == kinds.end() || decided.stroke_drawn[t.strokes + i] == 0) continue;
            ++d.lines;
            if (first) {
                d.at_depth = cam.depth({s.width.at.x, 0.0, s.width.at.z});
                d.paint = s.paint.day;
                first = false;
            }
            const auto half = [&](const view::Vec3& k) {
                return pymath::max(0.25, (s.width.metres * cam.focal()) / k.z) * density / 2;
            };
            for (std::uint32_t p = s.first; p < s.first + s.count; ++p) {
                const geo::Polyline& pl = ground.polylines[p];
                for (std::uint32_t k = 0; k + 1 < pl.count; ++k) {
                    const geo::P2& pa = ground.points[pl.first + k];
                    const geo::P2& pb = ground.points[pl.first + k + 1];
                    view::Vec3 ka = cam.cam({pa.x, pl.y, pa.z});
                    view::Vec3 kb = cam.cam({pb.x, pl.y, pb.z});
                    // the part ahead of the near plane
                    const double near = cam.near();
                    if (ka.z < near && kb.z < near) continue;
                    const auto at_near = [near](const view::Vec3& from, const view::Vec3& to) {
                        const double u = (near - from.z) / (to.z - from.z);
                        return view::Vec3{from.x + ((to.x - from.x) * u), from.y + ((to.y - from.y) * u), near};
                    };
                    if (ka.z < near) {
                        ka = at_near(ka, kb);
                    } else if (kb.z < near) {
                        kb = at_near(kb, ka);
                    }
                    const view::Vec2 sa = cam.pc(ka);
                    const view::Vec2 sb = cam.pc(kb);
                    d.pieces.push_back({.a = {sa.x * density, sa.y * density},
                                        .b = {sb.x * density, sb.y * density},
                                        .ha = half(ka),
                                        .hb = half(kb)});
                }
            }
        }
    }
    for (const std::string& m : session.take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
    return d;
}

/// How far (device px) the point (x, y) lies beyond the nearest piece's width there, the half width going along it as
/// it goes between its ends.
double beyond(const std::vector<Piece>& pieces, double x, double y) {
    double least = std::numeric_limits<double>::infinity();
    for (const Piece& p : pieces) {
        const double dx = p.b[0] - p.a[0];
        const double dy = p.b[1] - p.a[1];
        const double l2 = (dx * dx) + (dy * dy);
        const double t = l2 == 0.0 ? 0.0 : std::clamp((((x - p.a[0]) * dx) + ((y - p.a[1]) * dy)) / l2, 0.0, 1.0);
        const double d = pymath::hypot(p.a[0] + (t * dx) - x, p.a[1] + (t * dy) - y);
        least = std::min(least, d - (p.ha + ((p.hb - p.ha) * t)));
    }
    return least;
}

/// A line's ink against its width, row by row.
struct Ink {
    std::size_t rows = 0;
    std::size_t off = 0;
    double worst = 0;
};

/// The rows of a line upright on the screen where it is at most 4 device px wide, a few rows from its pieces' ends: in
/// each, the coverage its pixels show (each pixel's change from the frame without it, toward its paint, as a share of
/// the way there) summed across it, against its width at that row; off where they differ by over 15 % and 0.15 px.
Ink ink_of(const WindowDraw& with, const gpu::Pixels& without) {
    Ink ink;
    const std::uint32_t w = with.pixels.width;
    const std::array<double, 3> paint{static_cast<double>(with.paint.r), static_cast<double>(with.paint.g),
                                      static_cast<double>(with.paint.b)};
    for (const Piece& p : with.pieces) {
        const double y0 = std::min(p.a[1], p.b[1]) + 4.0;
        const double y1 = std::max(p.a[1], p.b[1]) - 4.0;
        for (auto y = static_cast<std::int64_t>(std::ceil(std::max(y0, 0.0)));
             y < static_cast<std::int64_t>(std::min(y1, static_cast<double>(with.pixels.height) - 1)); ++y) {
            const double t = (static_cast<double>(y) + 0.5 - p.a[1]) / (p.b[1] - p.a[1]);
            const double width = 2 * (p.ha + ((p.hb - p.ha) * t));
            if (width > 4.0) continue;
            const double cx = p.a[0] + ((p.b[0] - p.a[0]) * t);
            double sum = 0;
            for (auto x = static_cast<std::int64_t>(cx) - 12; x <= static_cast<std::int64_t>(cx) + 12; ++x) {
                if (x < 0 || std::cmp_greater_equal(x, w)) continue;
                const std::size_t i = (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x);
                double along = 0;
                double span = 0;
                for (std::size_t k = 0; k < 3; ++k) {
                    const double under = without.rgba[(i * 4) + k];
                    along += (static_cast<double>(with.pixels.rgba[(i * 4) + k]) - under) * (paint.at(k) - under);
                    span += (paint.at(k) - under) * (paint.at(k) - under);
                }
                if (span > 0) sum += along / span;
            }
            ++ink.rows;
            const double miss = std::abs(sum - width);
            ink.worst = std::max(ink.worst, miss / width);
            if (miss > (0.15 * width) + 0.15) ++ink.off;
        }
    }
    return ink;
}

} // namespace

TEST_CASE("ground: in the app's map a taxiway's and a road's lines are as wide as they lie, point by point, though "
          "their middle point lies behind the camera") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    struct Line {
        const char* name;
        double lon;
        std::vector<geo::StrokeKind> kinds; ///< its strokes' (a road's edge lines one, its lane lines' dashes another)
    };
    struct View {
        const char* name;
        double eye; ///< the latitude of the ground under the eye
        double pitch;
        double distance;
        bool behind; ///< the lines' middle point behind the eye
    };
    const std::array<Line, 2> lines{
        Line{.name = "the taxiway's line", .lon = taxiway_lon, .kinds = {geo::StrokeKind::taxi}},
        Line{.name = "the road's lines", .lon = road_lon, .kinds = {geo::StrokeKind::edges, geo::StrokeKind::lanes}}};
    // looking north at 60° from 150 m, the eye 100 m north of the middle point: the ground shows from some 66 m ahead
    // of the eye to 333 m, the point after the middle one 232 m ahead; and at 75° from 300 m, the eye 100 m south of
    // the first point: the lines from some 8 device px wide at the frame's bottom edge to under one far off (a road's
    // lines to their cut), drawn by their samples, their area and as hairlines on the way
    const std::array<View, 2> views{
        View{.name = "near", .eye = lats[2] + (100.0 / 110540.0), .pitch = 60.0, .distance = 150.0, .behind = true},
        View{.name = "far", .eye = lats[0] - (100.0 / 110540.0), .pitch = 75.0, .distance = 300.0, .behind = false}};
    for (const View& v : views) {
        for (const Line& l : lines) {
            const std::string name = std::format("{} from {}", l.name, v.name);
            CAPTURE(name);
            const double back = v.distance * pymath::sin(pymath::radians(v.pitch));
            style::CameraState camera = test::s1_camera(v.eye + (back / 110540.0), l.lon, 0.0, v.pitch, v.distance);
            camera.flags &= ~style::camera_flag::engine_mode;
            const auto with = draw_window(*session, path, camera, l.kinds);
            ATLAS_REQUIRE(with.has_value(), "the window was not drawn");
            style::CameraState no_lines = camera;
            no_lines.parts = style::camera_part::ground_lines;
            const auto without = draw_window(*session, path, no_lines, l.kinds);
            ATLAS_REQUIRE(without.has_value(), "the window without its lines was not drawn");
            CHECK(with->lines == l.kinds.size());
            CHECK(without->lines == 0);
            const std::uint32_t w = with->pixels.width;
            const std::uint32_t h = with->pixels.height;
            std::size_t drawn = 0;
            std::size_t spilt = 0;
            std::size_t bottom = 0;
            double worst = -std::numeric_limits<double>::infinity();
            for (std::uint32_t y = 0; y < h; ++y) {
                for (std::uint32_t x = 0; x < w; ++x) {
                    const std::size_t i = (std::size_t{y} * w) + x;
                    int diff = 0;
                    for (std::size_t k = 0; k < 3; ++k) {
                        diff = std::max(diff, std::abs(int(with->pixels.rgba[(i * 4) + k]) -
                                                       int(without->pixels.rgba[(i * 4) + k])));
                    }
                    if (diff <= 3) continue;
                    ++drawn;
                    if (y + (h / 50) >= h) ++bottom;
                    const double off = beyond(with->pieces, static_cast<double>(x) + 0.5, static_cast<double>(y) + 0.5);
                    worst = std::max(worst, off);
                    if (off > 1.5) ++spilt;
                }
            }
            MESSAGE(std::format("{}: its width point {:.1f} m deep; {} pixels drawn, {} in the bottom fiftieth, {} "
                                "beyond its width and a pixel and a half, the worst by {:.2f} px",
                                name, with->at_depth, drawn, bottom, spilt, worst));
            CHECK(drawn > 500);
            CHECK(spilt == 0);
            if (v.behind) {
                // the renders' point for the width lies behind the camera; the lines reach the frame's bottom edge
                CHECK(with->at_depth < 0.0);
                CHECK(bottom > 10);
            } else if (l.kinds.front() == geo::StrokeKind::taxi) {
                // the taxiway's line upright on the screen: each row as much ink as it is wide there
                const Ink ink = ink_of(*with, without->pixels);
                MESSAGE(std::format("{}: {} rows under 4 px, {} of them off by over 15 % and 0.15 px, the worst by "
                                    "{:.1f} %",
                                    name, ink.rows, ink.off, ink.worst * 100));
                CHECK(ink.rows > 200);
                CHECK(ink.off == 0);
            }
        }
    }
}

#endif
