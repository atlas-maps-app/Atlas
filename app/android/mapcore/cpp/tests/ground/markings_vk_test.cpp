// The app's map's painted lines on the host's Vulkan (passes/Lines.cpp's runs in metres, shaders/ground/ground.slang's
// stroke_vs): a window's taxiway and a big road, each of five points whose middle one lies behind a low camera looking
// north along it (the user, 2 November: the yellow at Schiphol "glitches out and covers and takes over much more area",
// and a road's lines at 52.69297, 4.77424). The renders' one width at the middle point is w · focal there, the whole
// line some 400 CSS px wide; on the ground each point is px at its own depth (render/regions.py's px a point at a
// time). Drawn by the ground alone with its lines and without them, every pixel the lines change lies within its line's
// own width there and a pixel and a half beyond, and the lines reach the frame's bottom edge, their segments through
// the near plane kept up to it rather than folded away. From higher up, every point ahead, the lines thin from 8 device
// px to under one, still within their widths, the taxiway's each row as much ink as it is wide there (each pixel
// covered as a line of its width there is: a hairline, the exact area or the samples). A coaster track's shadow beside
// them is such a line too (passes/Shadows.cpp's decide_tracks): the renders' 1.1 · focal over the depth of the track's
// middle point, on the ground 1.1 m at each of its points; drawn with the park's shadows and without them, every pixel
// its shadow and its posts' change lies within their widths there and a pixel and a half beyond, and the shadow fills
// its width. And the big road's asphalt is as wide as the road there (fill_vs, GROUND_WIDE), its edge lines on it,
// where engine mode lays it half as wide, as render/regions.py's ribbon(r['pts'], w / 2) does.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Coasters.hpp"
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
#include <functional>
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
/// A steel coaster's track between them, at layer 1: a lone way without its ride's profile, 5.4 m up (0.45 of 12 m).
constexpr double track_lon = 4.8890;

/// A theme park's window with a taxiway, a primary road of two lanes and a coaster's track along the latitudes, the
/// lines 815 m apart and the track 408 m from each; its pack written to the tests' scratch folder.
[[nodiscard]] std::string markings_window() {
    std::string taxiway;
    std::string road;
    std::string track;
    for (const double lat : lats) {
        taxiway += std::format(R"({}{{"lat": {}, "lon": {}}})", taxiway.empty() ? "" : ", ", lat, taxiway_lon);
        road += std::format(R"({}{{"lat": {}, "lon": {}}})", road.empty() ? "" : ", ", lat, road_lon);
        track += std::format(R"({}{{"lat": {}, "lon": {}}})", track.empty() ? "" : ", ", lat, track_lon);
    }
    const std::string data =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-11-02T08:00:00Z"}, "elements": [)"
        R"({"type": "way", "id": 31, "tags": {"aeroway": "taxiway"}, "geometry": [)" +
        taxiway + R"(]}, {"type": "way", "id": 40, "tags": {"highway": "primary", "lanes": "2"}, "geometry": [)" +
        road + R"(]}, {"type": "way", "id": 50, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [)" +
        track + "]}]}";
    // the park's rides: none with a profile
    static const load::RideProfiles rides{};
    load::WindowPlace place = load::place_of(window_tile);
    place.park = &rides;
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), window_tile, place);
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "markings-vk";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A camera looking north along the lines.
struct View {
    const char* name;
    double eye; ///< the latitude of the ground under the eye
    double pitch;
    double distance;
    bool behind; ///< the lines' middle point behind the eye
};

// looking north at 60° from 150 m, the eye 100 m north of the middle point: the ground shows from some 66 m ahead of
// the eye to 333 m, the point after the middle one 232 m ahead; and at 75° from 300 m, the eye 100 m south of the
// first point: the lines from some 8 device px wide at the frame's bottom edge to under one far off (a road's lines to
// their cut), drawn by their samples, their area and as hairlines on the way
constexpr std::array<View, 2> views{
    View{.name = "near", .eye = lats[2] + (100.0 / 110540.0), .pitch = 60.0, .distance = 150.0, .behind = true},
    View{.name = "far", .eye = lats[0] - (100.0 / 110540.0), .pitch = 75.0, .distance = 300.0, .behind = false}};

/// View v's camera over the line at `lon`, in the app's map.
style::CameraState camera_of(const View& v, double lon) {
    const double back = v.distance * pymath::sin(pymath::radians(v.pitch));
    style::CameraState camera = test::s1_camera(v.eye + (back / 110540.0), lon, 0.0, v.pitch, v.distance);
    camera.flags &= ~style::camera_flag::engine_mode;
    return camera;
}

/// A segment of a line as it lies on the screen: its ends (device px) and its half widths there.
struct Piece {
    std::array<double, 2> a{};
    std::array<double, 2> b{};
    double ha = 0;
    double hb = 0;
};

/// What a frame of the window drew: its pixels and, of the lines a test measures, each segment's part ahead of the
/// near plane as the ground lays it, px at each end's own depth (a coaster's posts' shadows apart); how many lines, how
/// deep the first's width point lay (the renders' point for its width), and its paint by day.
struct WindowDraw {
    gpu::Pixels pixels;
    std::vector<Piece> pieces;
    std::vector<Piece> posts;
    std::size_t lines = 0;
    double at_depth = 0;
    geo::Rgba paint;
};

/// What a test measures of a frame while its tiles are about: from the ground's decisions and the frame's density.
using Collect = std::function<void(const passes::GroundFrame&, double, WindowDraw&)>;

/// The window drawn by the ground alone at `camera` (in the app's map unless in engine mode), until its tile is built
/// and faded in.
std::optional<WindowDraw> draw_window(const test::Session& session, const std::string& path,
                                      const style::CameraState& camera, const Collect& collect) {
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
    WindowDraw d{.pixels = gpu::to_rgba8(*packed), .pieces = {}, .posts = {}, .lines = 0, .at_depth = 0, .paint = {}};
    const frame::FrameState& state = (*builder)->state();
    CHECK(state.grounded == ((camera.flags & style::camera_flag::engine_mode) == 0));
    collect(g->decided(), static_cast<double>(state.block.camera.frame.z), d);
    for (const std::string& m : session.take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
    return d;
}

/// The part ahead of the near plane of the segment from camera coordinates ka to kb, as the ground lays a line
/// `metres` wide there: px at each end's own depth, at least `least` CSS px.
void add_piece(const view::Camera& cam, view::Vec3 ka, view::Vec3 kb, double metres, double least, double density,
               std::vector<Piece>& pieces) {
    const double near = cam.near();
    if (ka.z < near && kb.z < near) return;
    const auto at_near = [near](const view::Vec3& from, const view::Vec3& to) {
        const double u = (near - from.z) / (to.z - from.z);
        return view::Vec3{from.x + ((to.x - from.x) * u), from.y + ((to.y - from.y) * u), near};
    };
    if (ka.z < near) {
        ka = at_near(ka, kb);
    } else if (kb.z < near) {
        kb = at_near(kb, ka);
    }
    const auto half = [&](const view::Vec3& k) {
        return pymath::max(least, (metres * cam.focal()) / k.z) * density / 2;
    };
    const view::Vec2 sa = cam.pc(ka);
    const view::Vec2 sb = cam.pc(kb);
    pieces.push_back(
        {.a = {sa.x * density, sa.y * density}, .b = {sb.x * density, sb.y * density}, .ha = half(ka), .hb = half(kb)});
}

/// Of the lines of `kinds` (the drawn ones): their segments' pieces, px(w) at least 0.25; the first's width point and
/// paint.
Collect line_pieces(std::vector<geo::StrokeKind> kinds) {
    return [kinds = std::move(kinds)](const passes::GroundFrame& decided, double density, WindowDraw& d) {
        for (const passes::DrawnTile& t : decided.tiles) {
            const view::Camera& cam = *t.cam;
            const geo::GroundTile& ground = *t.ground;
            for (std::uint32_t i = 0; i < ground.strokes.size(); ++i) {
                const geo::Stroke& s = ground.strokes[i];
                if (std::ranges::find(kinds, s.kind) == kinds.end() || decided.stroke_drawn[t.strokes + i] == 0) {
                    continue;
                }
                if (d.lines++ == 0) {
                    d.at_depth = cam.depth({s.width.at.x, 0.0, s.width.at.z});
                    d.paint = s.paint.day;
                }
                for (std::uint32_t p = s.first; p < s.first + s.count; ++p) {
                    const geo::Polyline& pl = ground.polylines[p];
                    for (std::uint32_t k = 0; k + 1 < pl.count; ++k) {
                        const geo::P2& pa = ground.points[pl.first + k];
                        const geo::P2& pb = ground.points[pl.first + k + 1];
                        add_piece(cam, cam.cam({pa.x, pl.y, pa.z}), cam.cam({pb.x, pl.y, pb.z}), s.width.metres, 0.25,
                                  density, d.pieces);
                    }
                }
            }
        }
    };
}

/// Of the coaster tracks' shadows (the decided ones): each track's points moved by the sun's offset, the whole track
/// one line 1.1 m wide, and each post's from its foot to its point moved, 0.5 m, at least 0.5 px (coasters.draw's);
/// how deep the first track's middle point lay at the ground, the renders' point for its width.
void track_pieces(const passes::GroundFrame& decided, double density, WindowDraw& d) {
    const double kx = -decided.light[0] / decided.light[1];
    const double kz = -decided.light[2] / decided.light[1];
    std::vector<std::pair<std::uint32_t, std::uint32_t>> tracks;
    for (const passes::TrackShadow& e : decided.track_shadows) {
        const passes::DrawnTile& t = decided.tiles[e.tile];
        const view::Camera& cam = *t.cam;
        const geo::GroundTile& g = *t.ground;
        const geo::Polyline& pl = g.polylines[g.tracks[e.track].polyline];
        const auto moved = [&](std::uint32_t k) {
            const geo::P2& p = g.points[pl.first + k];
            const double h = g.heights[pl.height + k];
            return cam.cam({p.x + (kx * h), pl.y, p.z + (kz * h)});
        };
        if (e.post) {
            const geo::P2& foot = g.points[pl.first + e.first];
            add_piece(cam, cam.cam({foot.x, pl.y, foot.z}), moved(e.first), 0.5, 0.5, density, d.posts);
            continue;
        }
        if (std::ranges::find(tracks, std::pair{e.tile, e.track}) != tracks.end()) continue;
        tracks.emplace_back(e.tile, e.track);
        if (d.lines++ == 0) {
            const geo::P2& mid = g.points[pl.first + (pl.count / 2)];
            d.at_depth = cam.cam({mid.x, 0.0, mid.z}).z;
        }
        for (std::uint32_t k = 0; k + 1 < pl.count; ++k)
            add_piece(cam, moved(k), moved(k + 1), 1.1, 0.5, density, d.pieces);
    }
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

/// Whether pixel i of a frame differs from the frame without its lines, by more than 3 in a channel.
bool changed(const gpu::Pixels& with, const gpu::Pixels& without, std::size_t i) {
    int diff = 0;
    for (std::size_t k = 0; k < 3; ++k) {
        diff = std::max(diff, std::abs(int(with.rgba[(i * 4) + k]) - int(without.rgba[(i * 4) + k])));
    }
    return diff > 3;
}

/// What a frame's lines changed: the pixels, those in the frame's bottom fiftieth, those beyond their line's width
/// there and a pixel and a half, and the worst by how far.
struct Change {
    std::size_t drawn = 0;
    std::size_t bottom = 0;
    std::size_t spilt = 0;
    double worst = -std::numeric_limits<double>::infinity();
};

Change change_of(const WindowDraw& with, const gpu::Pixels& without) {
    Change c;
    const std::uint32_t w = with.pixels.width;
    const std::uint32_t h = with.pixels.height;
    for (std::uint32_t y = 0; y < h; ++y) {
        for (std::uint32_t x = 0; x < w; ++x) {
            if (!changed(with.pixels, without, (std::size_t{y} * w) + x)) continue;
            ++c.drawn;
            if (y + (h / 50) >= h) ++c.bottom;
            const double px = static_cast<double>(x) + 0.5;
            const double py = static_cast<double>(y) + 0.5;
            const double off = std::min(beyond(with.pieces, px, py), beyond(with.posts, px, py));
            c.worst = std::max(c.worst, off);
            if (off > 1.5) ++c.spilt;
        }
    }
    return c;
}

/// A line's fill: the rows of its pieces where it is 6 device px wide or more, and of them those where its pixel at its
/// middle or 1.5 px within either edge is unchanged.
struct Filled {
    std::size_t rows = 0;
    std::size_t missed = 0;
};

Filled filled_of(const WindowDraw& with, const gpu::Pixels& without) {
    Filled f;
    const std::uint32_t w = with.pixels.width;
    const std::uint32_t h = with.pixels.height;
    for (const Piece& p : with.pieces) {
        if (p.a[1] == p.b[1]) continue;
        const double y0 = std::max(std::min(p.a[1], p.b[1]), 0.0);
        const double y1 = std::min(std::max(p.a[1], p.b[1]), static_cast<double>(h));
        for (auto y = static_cast<std::int64_t>(std::ceil(y0 - 0.5)); static_cast<double>(y) + 0.5 < y1; ++y) {
            const double t = (static_cast<double>(y) + 0.5 - p.a[1]) / (p.b[1] - p.a[1]);
            const double half = p.ha + ((p.hb - p.ha) * t);
            const double cx = p.a[0] + ((p.b[0] - p.a[0]) * t);
            if (half < 3.0 || cx - half < 0.0 || cx + half >= static_cast<double>(w)) continue;
            ++f.rows;
            for (const double x : {cx - half + 1.5, cx, cx + half - 1.5}) {
                const std::size_t i = (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x);
                if (!changed(with.pixels, without, i)) {
                    ++f.missed;
                    break;
                }
            }
        }
    }
    return f;
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
    const std::array<Line, 2> lines{
        Line{.name = "the taxiway's line", .lon = taxiway_lon, .kinds = {geo::StrokeKind::taxi}},
        Line{.name = "the road's lines", .lon = road_lon, .kinds = {geo::StrokeKind::edges, geo::StrokeKind::lanes}}};
    for (const View& v : views) {
        for (const Line& l : lines) {
            const std::string name = std::format("{} from {}", l.name, v.name);
            CAPTURE(name);
            const style::CameraState camera = camera_of(v, l.lon);
            const auto with = draw_window(*session, path, camera, line_pieces(l.kinds));
            ATLAS_REQUIRE(with.has_value(), "the window was not drawn");
            style::CameraState no_lines = camera;
            no_lines.parts = style::camera_part::ground_lines;
            const auto without = draw_window(*session, path, no_lines, line_pieces(l.kinds));
            ATLAS_REQUIRE(without.has_value(), "the window without its lines was not drawn");
            CHECK(with->lines == l.kinds.size());
            CHECK(without->lines == 0);
            const Change c = change_of(*with, without->pixels);
            MESSAGE(std::format("{}: its width point {:.1f} m deep; {} pixels drawn, {} in the bottom fiftieth, {} "
                                "beyond its width and a pixel and a half, the worst by {:.2f} px",
                                name, with->at_depth, c.drawn, c.bottom, c.spilt, c.worst));
            CHECK(c.drawn > 500);
            CHECK(c.spilt == 0);
            if (v.behind) {
                // the renders' point for the width lies behind the camera; the lines reach the frame's bottom edge
                CHECK(with->at_depth < 0.0);
                CHECK(c.bottom > 10);
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

TEST_CASE("ground: in the app's map a coaster track's shadow is as wide as it lies, point by point, though the "
          "track's middle point lies behind the camera") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    // the track under the camera, its shadow beside it some 10 m west and 16 m north (the sunny moment's sun): from
    // near, its middle point behind the eye, the renders' shadow 1.1 · focal wide (1550 CSS px); from far, every point
    // ahead, the renders' one width at the middle point a fifth of the shadow's at the frame's bottom edge
    for (const View& v : views) {
        CAPTURE(v.name);
        const style::CameraState camera = camera_of(v, track_lon);
        const auto with = draw_window(*session, path, camera, track_pieces);
        ATLAS_REQUIRE(with.has_value(), "the park was not drawn");
        style::CameraState no_parks = camera;
        no_parks.parts = style::camera_part::park_shadows;
        const auto without = draw_window(*session, path, no_parks, track_pieces);
        ATLAS_REQUIRE(without.has_value(), "the park without its shadows was not drawn");
        CHECK(with->lines == 1);
        CHECK(without->lines == 0);
        const Change c = change_of(*with, without->pixels);
        const Filled f = filled_of(*with, without->pixels);
        MESSAGE(std::format("the track's shadow from {}: its middle point {:.1f} m deep; {} posts' shadows; {} pixels "
                            "drawn, {} in the bottom fiftieth, {} beyond its width and a pixel and a half, the worst "
                            "by {:.2f} px; of {} rows 6 px wide or more, {} not filled",
                            v.name, with->at_depth, with->posts.size(), c.drawn, c.bottom, c.spilt, c.worst, f.rows,
                            f.missed));
        CHECK(c.drawn > 500);
        CHECK(c.spilt == 0);
        CHECK(f.rows > 200);
        CHECK(f.missed == 0);
        if (v.behind) {
            // the renders' point for the width lies behind the camera; the shadow reaches the frame's bottom edge
            CHECK(with->at_depth < 0.0);
            CHECK(c.bottom > 10);
        }
    }
}

TEST_CASE("ground: in the app's map a big road's asphalt is as wide as the road, under its edge lines; in engine mode "
          "half as wide, as the renders lay it") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    // the two-lane primary's 8 m: its centreline; its edge lines' middles 3.5 m either side; 4.6 m, beyond the road's
    // 4 m; and the plain ground 9 m off (the renders' asphalt reaches 2 m)
    constexpr std::array<double, 7> offsets{0.0, -3.5, 3.5, -4.6, 4.6, -9.0, 9.0};
    // a cross-section of the road: its samples on the screen (device px), by offset
    using Section = std::array<std::array<double, 2>, offsets.size()>;
    // looking north along the road at 50° from 100 m: the ground from some 40 m ahead of the eye to 160 m, a metre 25
    // to 60 device px
    const style::CameraState camera = test::s1_camera(lats[2], road_lon, 0.0, 50.0, 100.0);
    for (const bool engine : {false, true}) {
        CAPTURE(engine);
        style::CameraState c = camera;
        if (!engine) c.flags &= ~style::camera_flag::engine_mode;
        // the asphalt alone
        c.parts = style::camera_part::ground_lines;
        std::size_t roads = 0;
        std::vector<Section> sections;
        const auto across = [&](const passes::GroundFrame& decided, double density, WindowDraw&) {
            for (const passes::DrawnTile& t : decided.tiles) {
                const view::Camera& cam = *t.cam;
                const geo::GroundTile& g = *t.ground;
                for (const geo::Fill& f : g.fills) {
                    if (f.kind != geo::FillKind::big) continue;
                    ++roads;
                    // along its centreline every 4 m, each section where all of its samples lie under 300 m deep
                    for (std::uint32_t k = f.line; k + 1 < f.line + f.line_count; ++k) {
                        const geo::P2& a = g.points[k];
                        const geo::P2& b = g.points[k + 1];
                        const double length = pymath::hypot(b.x - a.x, b.z - a.z);
                        const double ux = (b.x - a.x) / length;
                        const double uz = (b.z - a.z) / length;
                        const auto steps = static_cast<std::uint32_t>(std::ceil(length / 4.0));
                        for (std::uint32_t j = 0; j < steps; ++j) {
                            const double s = 4.0 * static_cast<double>(j);
                            Section section{};
                            bool whole = true;
                            for (std::size_t i = 0; i < offsets.size() && whole; ++i) {
                                const double o = offsets.at(i);
                                const view::Vec3 q =
                                    cam.cam({a.x + (ux * s) - (uz * o), f.y, a.z + (uz * s) + (ux * o)});
                                whole = q.z > cam.near() && q.z < 300.0;
                                if (!whole) break;
                                const view::Vec2 p = cam.pc(q);
                                section.at(i) = {p.x * density, p.y * density};
                            }
                            if (whole) sections.push_back(section);
                        }
                    }
                }
            }
        };
        const auto drawn = draw_window(*session, path, c, across);
        ATLAS_REQUIRE(drawn.has_value(), "the window was not drawn");
        CHECK(roads == 1);
        const gpu::Pixels& px = drawn->pixels;
        const auto colour = [&](const std::array<double, 2>& at) -> std::optional<std::array<int, 3>> {
            if (at[0] < 0.0 || at[1] < 0.0 || at[0] >= static_cast<double>(px.width) ||
                at[1] >= static_cast<double>(px.height)) {
                return std::nullopt;
            }
            const std::size_t i = (static_cast<std::size_t>(at[1]) * px.width) + static_cast<std::size_t>(at[0]);
            return std::array<int, 3>{px.rgba[i * 4], px.rgba[(i * 4) + 1], px.rgba[(i * 4) + 2]};
        };
        const auto apart = [](const std::array<int, 3>& p, const std::array<int, 3>& q) {
            return ((p[0] - q[0]) * (p[0] - q[0])) + ((p[1] - q[1]) * (p[1] - q[1])) + ((p[2] - q[2]) * (p[2] - q[2]));
        };
        // each sample by the colour it is nearer: the section's centreline's (the asphalt) or its side's plain ground
        std::size_t told = 0;
        std::size_t lines_on = 0;
        std::size_t beside_on = 0;
        for (const Section& s : sections) {
            std::array<std::optional<std::array<int, 3>>, offsets.size()> c3;
            for (std::size_t i = 0; i < offsets.size(); ++i) c3.at(i) = colour(s.at(i));
            if (!std::ranges::all_of(c3, [](const auto& o) { return o.has_value(); })) continue;
            const std::array<int, 3>& asphalt = *c3[0];
            if (apart(asphalt, *c3[5]) < 400 || apart(asphalt, *c3[6]) < 400) continue;
            ++told;
            for (std::size_t side = 0; side < 2; ++side) {
                const std::array<int, 3>& ground = *c3.at(5 + side);
                if (apart(*c3.at(1 + side), asphalt) < apart(*c3.at(1 + side), ground)) ++lines_on;
                if (apart(*c3.at(3 + side), asphalt) < apart(*c3.at(3 + side), ground)) ++beside_on;
            }
        }
        MESSAGE(std::format("{}: of {} sections across the road, {} of their {} edge lines' middles on the asphalt and "
                            "{} of their {} points 0.6 m beyond the road",
                            engine ? "engine mode" : "the app's map", told, lines_on, 2 * told, beside_on, 2 * told));
        CHECK(told > 20);
        CHECK(beside_on == 0);
        if (engine) {
            CHECK(lines_on == 0);
        } else {
            CHECK(lines_on == 2 * told);
        }
    }
}

#endif
