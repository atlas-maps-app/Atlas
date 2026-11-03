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
// its width. And a sett street, the big road's asphalt and a runway's and the taxiway's pavements are as wide as their
// ways there (fill_vs, GROUND_WIDE), what is drawn on them on them (the big road's edge lines, the runway's threshold
// bars and designators), where engine mode lays them half as wide, as the renders' ribbon(pts, w / 2) does; a street
// along a canal as wide as its way on the land and not over the water (the streets' own draw, GROUND_STREETS: where
// the stencil holds no water, with weather and without), its bridge across the canal as wide as its way over it. A
// sett bridge passing under the camera has its lines (by day without weather, a fixed width) at the edges of its street
// as wide as its way there (each its mirror's words on) and from the frame's bottom edge on, their segments through the
// near plane kept (GROUND_NEAR_CLIP), where engine mode lays them at the edges of the renders' street and leaves those
// segments out, as the renders' path does; and its railings stand (with weather) at its street's edges in either kind
// of frame, though the window holds no street furniture (geometry/Things.cpp's build_things).
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Ground.hpp"
#include "geometry/Ribbons.hpp"
#include "geometry/Things.hpp"
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
#include "tests/text/text_support.hpp"
#include "tests/tiles/support.hpp"
#include "text/Fonts.hpp"
#include "text/GroundText.hpp"
#include "text/Outlines.hpp"
#include "text/Shaper.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Source.hpp"
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
/// A runway (18C/36C, 45 m) 204 m west of the road from the first point to the middle one (the road's frames from far
/// would show its centre line from some 1,400 m deep on), and a residential street of setts (5.5 m) 204 m east of the
/// taxiway, a bridge from the middle point on.
constexpr double runway_lon = 4.8800;
constexpr double street_lon = 4.8980;
/// A point of the bridge, 166 m on from where it starts.
constexpr double bridge_lat = 52.3705;
/// A street of plain asphalt (residential, 5.5 m) from 52.3690 to 52.3745 along a canal 20 m wide from 52.3700 to
/// 52.3740, the canal's west bank 2 m east of the street's centreline (the renders' street reaches 1.375 m from it, the
/// way 2.75 m), and the street's bridge across the canal at 52.3720, to 30 m east of the street.
constexpr double canal_street_lon = 4.8860;
constexpr double canal_bridge_lat = 52.3720;
/// Degrees of longitude a metre east there.
double east_metre() { return 1.0 / (111320.0 * pymath::cos(pymath::radians(canal_bridge_lat))); }

/// A theme park's window with a taxiway, a primary road of two lanes and a coaster's track along the latitudes, the
/// lines 815 m apart and the track 408 m from each, the runway, the sett street and its bridge, and the canal with its
/// street and bridge; its pack written to the tests' scratch folder.
[[nodiscard]] std::string markings_window() {
    std::string taxiway;
    std::string road;
    std::string track;
    std::string runway;
    std::string street;
    std::string bridge;
    for (std::size_t i = 0; i < lats.size(); ++i) {
        const double lat = lats.at(i);
        taxiway += std::format(R"({}{{"lat": {}, "lon": {}}})", taxiway.empty() ? "" : ", ", lat, taxiway_lon);
        road += std::format(R"({}{{"lat": {}, "lon": {}}})", road.empty() ? "" : ", ", lat, road_lon);
        track += std::format(R"({}{{"lat": {}, "lon": {}}})", track.empty() ? "" : ", ", lat, track_lon);
        if (i <= 2) runway += std::format(R"({}{{"lat": {}, "lon": {}}})", runway.empty() ? "" : ", ", lat, runway_lon);
        if (i <= 2) street += std::format(R"({}{{"lat": {}, "lon": {}}})", street.empty() ? "" : ", ", lat, street_lon);
        if (i >= 2) bridge += std::format(R"({}{{"lat": {}, "lon": {}}})", bridge.empty() ? "" : ", ", lat, street_lon);
    }
    const std::string data =
        R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-11-02T08:00:00Z"}, "elements": [)"
        R"({"type": "way", "id": 31, "tags": {"aeroway": "taxiway"}, "geometry": [)" +
        taxiway + R"(]}, {"type": "way", "id": 40, "tags": {"highway": "primary", "lanes": "2"}, "geometry": [)" +
        road + R"(]}, {"type": "way", "id": 50, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [)" +
        track +
        R"(]}, {"type": "way", "id": 32, "tags": {"aeroway": "runway", "ref": "18C/36C", "width": "45"}, "geometry": [)" +
        runway +
        R"(]}, {"type": "way", "id": 41, "tags": {"highway": "residential", "surface": "sett"}, "geometry": [)" +
        street +
        R"(]}, {"type": "way", "id": 42, "tags": {"highway": "residential", "surface": "sett", "bridge": "yes"}, )"
        R"("geometry": [)" +
        bridge + R"(]}, {"type": "way", "id": 43, "tags": {"highway": "residential"}, "geometry": [)" +
        std::format(R"({{"lat": 52.3690, "lon": {0:.7f}}}, {{"lat": 52.3745, "lon": {0:.7f}}})", canal_street_lon) +
        R"(]}, {"type": "way", "id": 44, "tags": {"highway": "residential", "bridge": "yes"}, "geometry": [)" +
        std::format(R"({{"lat": {0:.7f}, "lon": {1:.7f}}}, {{"lat": {0:.7f}, "lon": {2:.7f}}})", canal_bridge_lat,
                    canal_street_lon, canal_street_lon + (30.0 * east_metre())) +
        R"(]}, {"type": "way", "id": 60, "tags": {"natural": "water", "water": "canal"}, "geometry": [)" +
        std::format(R"({{"lat": 52.3700, "lon": {0:.7f}}}, {{"lat": 52.3700, "lon": {1:.7f}}}, )"
                    R"({{"lat": 52.3740, "lon": {1:.7f}}}, {{"lat": 52.3740, "lon": {0:.7f}}}, )"
                    R"({{"lat": 52.3700, "lon": {0:.7f}}})",
                    canal_street_lon + (2.0 * east_metre()), canal_street_lon + (22.0 * east_metre())) +
        R"(]}]})";
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

/// The sunny moment without weather: a bridge's lines' (by day without weather; its railings stand with weather).
style::ThemeBlock dry() {
    style::ThemeBlock b = test::sunny();
    b.flags &= ~(style::theme_flag::weather | style::theme_flag::water_gradient);
    return b;
}

/// The window drawn by the ground alone (and the street furniture where `things`) at `camera` (in the app's map unless
/// in engine mode) at `theme`, until its tile is built and faded in.
std::optional<WindowDraw> draw_window(const test::Session& session, const std::string& path,
                                      const style::CameraState& camera, const Collect& collect,
                                      const style::ThemeBlock& theme = test::sunny(), bool things = false) {
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    if (!cache) return std::nullopt;
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    constexpr std::array<std::string_view, 4> with_things{"composite", "frosted", "ground", "things"};
    std::vector<std::unique_ptr<frame::Pass>> passes =
        things ? frame::make_passes(with_things) : frame::make_passes(ground_only);
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

/// A thin line's rows: those its pieces cross inside the frame, and of them those where no pixel within its width and
/// a pixel and a half of its middle changed.
Filled rows_of(const WindowDraw& with, const gpu::Pixels& without) {
    Filled f;
    const std::uint32_t w = with.pixels.width;
    const std::uint32_t h = with.pixels.height;
    for (const Piece& p : with.pieces) {
        if (p.a[1] == p.b[1]) continue;
        const double y0 = std::max(std::min(p.a[1], p.b[1]), 0.0);
        const double y1 = std::min(std::max(p.a[1], p.b[1]), static_cast<double>(h));
        for (auto y = static_cast<std::int64_t>(std::ceil(y0 - 0.5)); static_cast<double>(y) + 0.5 < y1; ++y) {
            const double t = (static_cast<double>(y) + 0.5 - p.a[1]) / (p.b[1] - p.a[1]);
            const double reach = p.ha + ((p.hb - p.ha) * t) + 1.5;
            const double cx = p.a[0] + ((p.b[0] - p.a[0]) * t);
            if (cx - reach < 0.0 || cx + reach >= static_cast<double>(w)) continue;
            ++f.rows;
            bool hit = false;
            for (auto x = static_cast<std::int64_t>(cx - reach); !hit && static_cast<double>(x) <= cx + reach; ++x) {
                hit = changed(with.pixels, without, (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x));
            }
            if (!hit) ++f.missed;
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

/// A fill's centreline: its line, or (a street keeps none) the middles of its ribbon's points and their mirrors.
std::vector<geo::P2> centre_of(const geo::GroundTile& g, const geo::Fill& f) {
    std::vector<geo::P2> out;
    if (f.line_count > 1) {
        const std::span<const geo::P2> line = std::span(g.points).subspan(f.line, f.line_count);
        out.assign(line.begin(), line.end());
        return out;
    }
    for (std::uint32_t k = 0; k < f.count / 2; ++k) {
        const geo::P2& p = g.points[f.first + k];
        const geo::P2& m = g.points[f.first + f.count - 1 - k];
        out.push_back({.x = (p.x + m.x) / 2, .z = (p.z + m.z) / 2});
    }
    return out;
}

/// How far a run's ink reaches across its baseline from its anchor, in its units (a designator's: metres).
double reach_of(const text::Fonts& fonts, const text::TextRun& run) {
    const auto style = run.shape_style(text::Rounding::floor);
    if (!style) return 0;
    const auto shaped = text::shape(fonts, *style, run.text);
    const auto instance = fonts.instance(style->font);
    if (!shaped || !instance) return 0;
    const double scale = static_cast<double>(style->size) / static_cast<double>((*instance)->units_per_em());
    double reach = 0;
    for (std::size_t i = 0; i < shaped->glyphs.size(); ++i) {
        const text::GlyphOutline o = text::outline(**instance, shaped->glyphs[i]);
        if (o.empty()) continue;
        const auto x = static_cast<double>(shaped->anchored_x(i, style->anchor));
        reach = std::max({reach, std::abs(x + (static_cast<double>(o.box.x0) * scale)),
                          std::abs(x + (static_cast<double>(o.box.x1) * scale))});
    }
    return reach;
}

/// A quad on the screen (device px, convex, its corners in order) and how far beyond it a pixel of it may lie.
struct Quad {
    std::array<std::array<double, 2>, 4> c{};
    double margin = 0;
};

/// Whether (x, y) lies within quad q or its margin of it.
bool within(const Quad& q, double x, double y) {
    bool left = false;
    bool right = false;
    double least = std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < 4; ++i) {
        const std::array<double, 2>& a = q.c.at(i);
        const std::array<double, 2>& b = q.c.at((i + 1) % 4);
        const double dx = b[0] - a[0];
        const double dy = b[1] - a[1];
        const double cross = (dx * (y - a[1])) - (dy * (x - a[0]));
        left = left || cross > 0;
        right = right || cross < 0;
        const double l2 = (dx * dx) + (dy * dy);
        const double t = l2 == 0.0 ? 0.0 : std::clamp((((x - a[0]) * dx) + ((y - a[1]) * dy)) / l2, 0.0, 1.0);
        least = std::min(least, pymath::hypot(a[0] + (t * dx) - x, a[1] + (t * dy) - y));
    }
    return !(left && right) || least <= q.margin;
}

/// Whether (x, z) lies in one of the tile's water rings (the window's canal: no islands).
bool in_water(const geo::GroundTile& g, double x, double z) {
    for (const geo::Fill& f : g.fills) {
        if (f.kind != geo::FillKind::water) continue;
        const std::span<const geo::P2> ring = std::span(g.points).subspan(f.first, f.count);
        bool in = false;
        for (std::size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            const geo::P2& a = ring[i];
            const geo::P2& b = ring[j];
            if ((a.z > z) != (b.z > z) && x < (((b.x - a.x) * (z - a.z)) / (b.z - a.z)) + a.x) in = !in;
        }
        if (in) return true;
    }
    return false;
}

/// How far (x, z) lies from the polyline `line`.
double distance_to(const std::vector<geo::P2>& line, double x, double z) {
    double least = std::numeric_limits<double>::infinity();
    for (std::size_t k = 0; k + 1 < line.size(); ++k) {
        const geo::P2& a = line[k];
        const geo::P2& b = line[k + 1];
        const double dx = b.x - a.x;
        const double dz = b.z - a.z;
        const double l2 = (dx * dx) + (dz * dz);
        const double t = l2 == 0.0 ? 0.0 : std::clamp((((x - a.x) * dx) + ((z - a.z) * dz)) / l2, 0.0, 1.0);
        least = std::min(least, pymath::hypot(a.x + (t * dx) - x, a.z + (t * dz) - z));
    }
    return least;
}

/// Whether a tile of the pack at `path` holds street furniture (a thing layer).
bool furnished(const std::string& path) {
    namespace tl = atlas::tiles;
    auto pack = test::open_pack(path);
    ATLAS_REQUIRE_OR_RETURN(true, pack.has_value(), tl::describe(pack.error()));
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(8U << 20U, Growth::grows, "markings test");
    for (const tl::ChunkRef& chunk : pack->chunks().chunks()) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE_OR_RETURN(true, tile.has_value(), tl::describe(tile.error()));
        if (tile->layer(tl::LayerId::thing) != nullptr) return true;
    }
    return false;
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

TEST_CASE("ground: in the app's map a street, a big road's asphalt and a runway's and a taxiway's pavements are as "
          "wide as their ways, what is drawn on them on them; in engine mode half as wide, as the renders lay them") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    const text::Fonts* fonts = test::bundled_fonts();
    ATLAS_REQUIRE(fonts != nullptr);
    // a way measured across: its fill's kind, the camera, and its samples' offsets from the centreline: the plain
    // ground's either side, those within the way's width but beyond the renders' half of it, those just beyond the way;
    // its sections from 2 m past its first point to `reach`
    struct Across {
        const char* name;
        geo::FillKind kind;
        style::CameraState camera;
        double plain;
        std::vector<double> inside;
        std::vector<double> beyond;
        double reach;
    };
    const std::array<Across, 4> ways{
        // the two-lane primary's 8 m: its edge lines' middles 3.5 m either side, 4.6 m beyond the road's 4 m, the plain
        // ground 9 m off (the renders' asphalt reaches 2 m); looking north along it at 50° from 100 m, the ground from
        // some 40 m ahead of the eye to 160 m, a metre 25 to 60 device px
        Across{.name = "the primary's asphalt",
               .kind = geo::FillKind::big,
               .camera = test::s1_camera(lats[2], road_lon, 0.0, 50.0, 100.0),
               .plain = 9.0,
               .inside = {3.5},
               .beyond = {4.6},
               .reach = 2000.0},
        // the runway's 45 m: 0.6 m beyond its outermost threshold bars (to 3 + 7 · 16.5 / 8 + 0.9 = 18.34 m) and 0.5 m
        // within its edge, 0.6 m beyond it, the grass 26 m off (the renders' pavement reaches 11.25 m, under the fifth
        // of the eight bars on each side); from 300 m at 70° over its first 120 m, 190 m to 300 m deep, its south
        // end's bars from 6 m to 36
        Across{.name = "the runway's pavement",
               .kind = geo::FillKind::runway,
               .camera = test::s1_camera(lats[0] + (120.0 / 110540.0), runway_lon, 0.0, 70.0, 300.0),
               .plain = 26.0,
               .inside = {18.94, 22.0},
               .beyond = {23.1},
               .reach = 120.0},
        // the taxiway's 23 m: 0.5 m within its edge, 0.6 m beyond it, the plain ground 25 m off; at 60° from 250 m
        Across{.name = "the taxiway's pavement",
               .kind = geo::FillKind::taxiway,
               .camera = test::s1_camera(lats[2], taxiway_lon, 0.0, 60.0, 250.0),
               .plain = 25.0,
               .inside = {11.0},
               .beyond = {12.1},
               .reach = 2000.0},
        // the sett street's 5.5 m: 0.3 m within its edge, 0.6 m beyond it, the plain ground 8 m off (the renders'
        // street reaches 1.375 m); at 50° from 80 m
        Across{.name = "the sett street",
               .kind = geo::FillKind::road,
               .camera = test::s1_camera(lats[1], street_lon, 0.0, 50.0, 80.0),
               .plain = 8.0,
               .inside = {2.45},
               .beyond = {3.35},
               .reach = 2000.0}};
    for (const Across& way : ways) {
        for (const bool engine : {false, true}) {
            CAPTURE(way.name);
            CAPTURE(engine);
            style::CameraState c = way.camera;
            if (!engine) c.flags &= ~style::camera_flag::engine_mode;
            // the pavements alone: no lines, courses or names on them
            c.parts = style::camera_part::ground_lines | style::camera_part::setts | style::camera_part::ground_text;
            // each offset's sample of a section: the centreline's, the plain ground's either side, then the inside and
            // beyond ones, each to the left then to the right
            std::vector<double> offsets{0.0, -way.plain, way.plain};
            for (const double o : way.inside) offsets.insert(offsets.end(), {-o, o});
            for (const double o : way.beyond) offsets.insert(offsets.end(), {-o, o});
            std::size_t fills = 0;
            std::size_t designators = 0;
            std::vector<std::vector<std::array<double, 2>>> sections;
            const auto across = [&](const passes::GroundFrame& decided, double density, WindowDraw&) {
                for (const passes::DrawnTile& t : decided.tiles) {
                    const view::Camera& cam = *t.cam;
                    const geo::GroundTile& g = *t.ground;
                    for (const geo::Fill& f : g.fills) {
                        if (f.kind != way.kind) continue;
                        ++fills;
                        const std::vector<geo::P2> line = centre_of(g, f);
                        // along its centreline every 4 m, each section where all of its samples lie under 300 m deep
                        double along = 0;
                        for (std::size_t k = 0; k + 1 < line.size(); ++k) {
                            const geo::P2& a = line[k];
                            const geo::P2& b = line[k + 1];
                            const double length = pymath::hypot(b.x - a.x, b.z - a.z);
                            const double ux = (b.x - a.x) / length;
                            const double uz = (b.z - a.z) / length;
                            const auto steps = static_cast<std::uint32_t>(std::ceil(length / 4.0));
                            for (std::uint32_t j = 0; j < steps; ++j) {
                                const double s = 4.0 * static_cast<double>(j);
                                if (along + s < 2.0 || along + s > way.reach) continue;
                                std::vector<std::array<double, 2>> section;
                                for (const double o : offsets) {
                                    const view::Vec3 q =
                                        cam.cam({a.x + (ux * s) - (uz * o), f.y, a.z + (uz * s) + (ux * o)});
                                    if (q.z <= cam.near() || q.z >= 300.0) break;
                                    const view::Vec2 p = cam.pc(q);
                                    section.push_back({p.x * density, p.y * density});
                                }
                                if (section.size() == offsets.size()) sections.push_back(std::move(section));
                            }
                            along += length;
                        }
                    }
                    // a runway's designators (60 m in, 18 m tall): their ink across it within its width, beyond the
                    // renders' half of it
                    for (const geo::RunwayRef& r : way.kind == geo::FillKind::runway && !engine
                                                       ? std::span<const geo::RunwayRef>(g.runways)
                                                       : std::span<const geo::RunwayRef>()) {
                        const geo::Fill& f = g.fills[r.pavement];
                        const std::vector<geo::P2> line = centre_of(g, f);
                        text::Runway runway{.points = {}, .ref = r.ref};
                        for (const geo::P2& p : line) runway.points.push_back({p.x, p.z});
                        for (int end = 0; end < 2; ++end) {
                            const auto run = text::runway_designator(cam, runway, 0xF4F3EE, end);
                            if (!run) continue;
                            ++designators;
                            const double reach = reach_of(*fonts, *run);
                            MESSAGE(std::format("the designator {}: its ink {:.2f} m from the centre line", run->text,
                                                reach));
                            CHECK(reach > 45.0 / 4);
                            CHECK(reach < 45.0 / 2);
                        }
                    }
                }
            };
            const auto drawn = draw_window(*session, path, c, across);
            ATLAS_REQUIRE(drawn.has_value(), "the window was not drawn");
            CHECK(fills > 0);
            if (way.kind == geo::FillKind::runway && !engine) CHECK(designators == 2);
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
                return ((p[0] - q[0]) * (p[0] - q[0])) + ((p[1] - q[1]) * (p[1] - q[1])) +
                       ((p[2] - q[2]) * (p[2] - q[2]));
            };
            // each sample by the colour it is nearer: the section's centreline's (the pavement) or its side's plain
            // ground
            std::size_t told = 0;
            std::size_t inside = 0;
            std::size_t inside_on = 0;
            std::size_t beyond = 0;
            std::size_t beyond_on = 0;
            for (const std::vector<std::array<double, 2>>& section : sections) {
                std::vector<std::array<int, 3>> c3;
                for (const std::array<double, 2>& at : section) {
                    if (const auto c1 = colour(at)) c3.push_back(*c1);
                }
                if (c3.size() != section.size()) continue;
                const std::array<int, 3>& pavement = c3[0];
                if (apart(pavement, c3[1]) < 400 || apart(pavement, c3[2]) < 400) continue;
                ++told;
                for (std::size_t i = 3; i < c3.size(); ++i) {
                    const std::array<int, 3>& ground = c3.at(1 + ((i - 3) % 2));
                    const bool on = apart(c3[i], pavement) < apart(c3[i], ground);
                    if (i < 3 + (2 * way.inside.size())) {
                        ++inside;
                        inside_on += on ? 1U : 0U;
                    } else {
                        ++beyond;
                        beyond_on += on ? 1U : 0U;
                    }
                }
            }
            MESSAGE(std::format("{}, {}: of {} sections across it, {} of their {} points within the way's width but "
                                "beyond the renders' half of it on the pavement and {} of their {} points beyond the "
                                "way",
                                way.name, engine ? "engine mode" : "the app's map", told, inside_on, inside, beyond_on,
                                beyond));
            CHECK(told > 20);
            CHECK(beyond_on == 0);
            CHECK(inside_on == (engine ? 0 : inside));
        }
    }
}

TEST_CASE("ground: in the app's map a bridge's lines lie at the edges of its street as wide as its way and reach the "
          "frame's bottom edge where the bridge passes under the camera; in engine mode they lie at the edges of the "
          "renders' half as wide street and start at its first point ahead of the near plane, as the renders' path "
          "does") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    // looking north along the sett bridge at 60° from 200 m, the eye 100 m over it from where it starts, by day without
    // weather (when its lines are drawn): its first point behind the near plane, the next one 232 m ahead of the eye
    // and on the screen, the ground from some 90 m ahead; its lines 1.1 m up, a fixed width, at the renders'
    // ribbon(pts, w / 2 + 0.3), 1.525 m from its centreline, and in the app's map twice as far, 3.05 m
    const style::CameraState camera = test::s1_camera(lats[2] + (273.0 / 110540.0), street_lon, 0.0, 60.0, 200.0);
    for (const bool engine : {false, true}) {
        CAPTURE(engine);
        style::CameraState c = camera;
        if (!engine) c.flags &= ~style::camera_flag::engine_mode;
        c.parts = style::camera_part::setts;
        // the lines as the frame lays them: in the app's map each segment's part ahead of the near plane, its points
        // twice as far from the middle of them and their mirrors on the other line, in engine mode the segments with
        // both ends ahead of it; how many segments pass the near plane; and the other kind of frame's lines, their
        // segments ahead of it
        std::size_t lines = 0;
        std::size_t through = 0;
        std::vector<Piece> there;
        const auto pieces = [&](const passes::GroundFrame& decided, double density, WindowDraw& d) {
            for (const passes::DrawnTile& t : decided.tiles) {
                const view::Camera& cam = *t.cam;
                const geo::GroundTile& g = *t.ground;
                // the sett bridge's deck: three points (the canal's bridge has two)
                std::vector<geo::P2> deck;
                for (const geo::Fill& f : g.fills) {
                    if (f.kind == geo::FillKind::road && f.y > 0.3 && centre_of(g, f).size() == 3)
                        deck = centre_of(g, f);
                }
                for (std::uint32_t i = 0; i < g.strokes.size(); ++i) {
                    const geo::Stroke& s = g.strokes[i];
                    if (s.kind != geo::StrokeKind::bridge || decided.stroke_drawn[t.strokes + i] == 0) continue;
                    const geo::P2& start = g.points[g.polylines[s.first].first];
                    if (deck.empty() || distance_to(deck, start.x, start.z) > 5.0) continue;
                    ++lines;
                    REQUIRE(s.count == 1);
                    REQUIRE((s.mirror == 1 || s.mirror == -1));
                    const geo::Polyline& pl = g.polylines[s.first];
                    const geo::Polyline& ml =
                        g.polylines[static_cast<std::uint32_t>(static_cast<std::int64_t>(s.first) + s.mirror)];
                    REQUIRE(ml.count == pl.count);
                    for (const bool wide : {false, true}) {
                        const auto at = [&](std::uint32_t k) {
                            const geo::P2 p = g.points[pl.first + k];
                            const geo::P2 m = g.points[ml.first + k];
                            return wide ? geo::P2{.x = (2 * p.x) - (0.5 * (p.x + m.x)),
                                                  .z = (2 * p.z) - (0.5 * (p.z + m.z))}
                                        : p;
                        };
                        for (std::uint32_t k = 0; k + 1 < pl.count; ++k) {
                            const geo::P2 a = at(k);
                            const geo::P2 b = at(k + 1);
                            const view::Vec3 ka = cam.cam({a.x, pl.y, a.z});
                            const view::Vec3 kb = cam.cam({b.x, pl.y, b.z});
                            const bool both = ka.z >= cam.near() && kb.z >= cam.near();
                            if (wide != engine) {
                                if (!both && (ka.z >= cam.near() || kb.z >= cam.near())) ++through;
                                if (!engine || both) add_piece(cam, ka, kb, 0.0, s.width.css, density, d.pieces);
                            } else if (both) {
                                add_piece(cam, ka, kb, 0.0, s.width.css, density, there);
                            }
                        }
                    }
                }
            }
        };
        const auto with = draw_window(*session, path, c, pieces, dry());
        ATLAS_REQUIRE(with.has_value(), "the window was not drawn");
        style::CameraState no_lines = c;
        no_lines.parts |= style::camera_part::ground_lines;
        const auto without =
            draw_window(*session, path, no_lines, [](const passes::GroundFrame&, double, WindowDraw&) {}, dry());
        ATLAS_REQUIRE(without.has_value(), "the window was not drawn");
        CHECK(lines == 2);
        CHECK(through == 2);
        const Change change = change_of(*with, without->pixels);
        const Filled rows = rows_of(*with, without->pixels);
        std::size_t on_there = 0;
        for (std::size_t i = 0; i < std::size_t{with->pixels.width} * with->pixels.height; ++i) {
            if (!changed(with->pixels, without->pixels, i)) continue;
            const std::size_t row = i / with->pixels.width;
            const double x = static_cast<double>(i % with->pixels.width) + 0.5;
            const double y = static_cast<double>(row) + 0.5;
            if (beyond(there, x, y) <= 0.0) ++on_there;
        }
        MESSAGE(
            std::format("{}: the bridge's lines change {} pixels, {} in the frame's bottom fiftieth, {} beyond "
                        "their width and 1.5 px (the worst {:.2f} px beyond it), {} on the other kind of frame's "
                        "lines; of the {} rows their pieces cross, {} without a changed pixel within their width and "
                        "1.5 px",
                        engine ? "engine mode" : "the app's map", change.drawn, change.bottom, change.spilt,
                        change.worst, on_there, rows.rows, rows.missed));
        CHECK(change.drawn > 500);
        CHECK(change.spilt == 0);
        CHECK(on_there == 0);
        CHECK(rows.rows > 500);
        CHECK(rows.missed == 0);
        if (engine) {
            CHECK(change.bottom == 0);
        } else {
            CHECK(change.bottom > 0);
        }
    }
}

TEST_CASE("things: in the app's map a bridge's railings stand at the edges of its street as wide as its way; in engine "
          "mode at the edges of the renders' half as wide; in either, though its window holds no street furniture") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    // geometry::build_things built a tile's bridges' railings only where the tile held a thing
    CHECK_FALSE(furnished(path));
    // looking north along the sett bridge at 50° from 60 m, with weather (when its railings stand): its rails 0.12 m
    // and 1 m over its foot along the renders' ribbon(pts, w / 2 + 0.15), 1.45 m from its centreline, and in the app's
    // map along ribbon(pts, w + 0.3), 2.9 m from it
    const style::CameraState camera = test::s1_camera(bridge_lat, street_lon, 0.0, 50.0, 60.0);
    for (const bool engine : {false, true}) {
        CAPTURE(engine);
        style::CameraState c = camera;
        if (!engine) c.flags &= ~style::camera_flag::engine_mode;
        c.parts = style::camera_part::setts;
        // each side's pieces of 2 m on the screen, between their rails' heights
        std::vector<Quad> pieces;
        std::size_t decks = 0;
        const auto quads = [&](const passes::GroundFrame& decided, double density, WindowDraw&) {
            for (const passes::DrawnTile& t : decided.tiles) {
                const view::Camera& cam = *t.cam;
                const geo::GroundTile& g = *t.ground;
                for (const geo::Fill& f : g.fills) {
                    if (f.kind != geo::FillKind::road || f.y < 0.3) continue;
                    // the sett bridge's deck: three points (the canal's bridge has two)
                    const std::vector<geo::P2> centre = centre_of(g, f);
                    if (centre.size() != 3) continue;
                    ++decks;
                    const std::size_t n = centre.size();
                    std::vector<geo::P2> side;
                    geo::ribbon(centre, engine ? (5.5 / 2) + 0.15 : 5.5 + 0.3, side);
                    for (std::size_t s = 0; s < 2; ++s) {
                        for (std::size_t k = 0; k + 1 < n; ++k) {
                            const geo::P2 a = s == 0 ? side[k] : side[(2 * n) - 1 - k];
                            const geo::P2 b = s == 0 ? side[k + 1] : side[(2 * n) - 2 - k];
                            const double length = pymath::hypot(b.x - a.x, b.z - a.z);
                            const auto steps = static_cast<std::uint32_t>(std::ceil(length / 2.0));
                            for (std::uint32_t j = 0; j < steps; ++j) {
                                const double u0 = (2.0 * static_cast<double>(j)) / length;
                                const double u1 = std::min(1.0, (2.0 * static_cast<double>(j + 1)) / length);
                                const std::array<geo::P2, 2> ends{
                                    geo::P2{.x = a.x + ((b.x - a.x) * u0), .z = a.z + ((b.z - a.z) * u0)},
                                    geo::P2{.x = a.x + ((b.x - a.x) * u1), .z = a.z + ((b.z - a.z) * u1)}};
                                Quad q;
                                bool ahead = true;
                                double nearest = std::numeric_limits<double>::infinity();
                                for (std::size_t i = 0; i < 4 && ahead; ++i) {
                                    const geo::P2& e = ends.at(i == 0 || i == 3 ? 0 : 1);
                                    const double y = geo::railing_foot + (i < 2 ? 0.12 : 1.0);
                                    const view::Vec3 k3 = cam.cam({e.x, y, e.z});
                                    ahead = k3.z > cam.near();
                                    nearest = std::min(nearest, k3.z);
                                    const view::Vec2 p = cam.pc(k3);
                                    q.c.at(i) = {p.x * density, p.y * density};
                                }
                                if (!ahead) continue;
                                // the rails' and posts' width there (max(0.4, 0.045 · s)), half of it, and 1.5 px
                                q.margin = (pymath::max(0.4, 0.045 * cam.focal() / nearest) * density / 2) + 1.5;
                                pieces.push_back(q);
                            }
                        }
                    }
                }
            }
        };
        const auto with = draw_window(*session, path, c, quads, test::sunny(), true);
        ATLAS_REQUIRE(with.has_value(), "the window was not drawn");
        style::CameraState no_things = c;
        no_things.parts |= style::camera_part::furniture;
        const auto without = draw_window(
            *session, path, no_things, [](const passes::GroundFrame&, double, WindowDraw&) {}, test::sunny(), true);
        ATLAS_REQUIRE(without.has_value(), "the window was not drawn");
        CHECK(decks == 1);
        const gpu::Pixels& px = with->pixels;
        std::size_t drawn = 0;
        std::size_t spilt = 0;
        for (std::size_t i = 0; i < std::size_t{px.width} * px.height; ++i) {
            if (!changed(px, without->pixels, i)) continue;
            ++drawn;
            const std::size_t row = i / px.width;
            const double x = static_cast<double>(i % px.width) + 0.5;
            const double y = static_cast<double>(row) + 0.5;
            if (std::ranges::none_of(pieces, [&](const Quad& q) { return within(q, x, y); })) ++spilt;
        }
        MESSAGE(std::format("{}: the bridge's railings change {} pixels, {} beyond their pieces and their rails' width",
                            engine ? "engine mode" : "the app's map", drawn, spilt));
        CHECK(drawn > 500);
        CHECK(spilt == 0);
    }
}

TEST_CASE("ground: in the app's map a street is as wide as its way on the land and kept off the water, and its "
          "bridge as wide as its way over the water; in engine mode both half as wide, as the renders lay them") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = markings_window();
    ATLAS_REQUIRE(!path.empty());
    // looking north along the canal's street at 50° from 80 m, its target on the canal's bank 30 m short of the bridge,
    // and east along the bridge from the street's west, its target 12 m out over the water; with weather and without
    // (the streets' draw tests the water's w0 bit, or its count)
    const std::array<style::CameraState, 2> cameras{
        test::s1_camera(canal_bridge_lat - (30.0 / 110540.0), canal_street_lon + (2.0 * east_metre()), 0.0, 50.0, 80.0),
        test::s1_camera(canal_bridge_lat, canal_street_lon + (12.0 * east_metre()), 90.0, 50.0, 80.0)};
    for (const bool weather : {true, false}) {
        for (const bool engine : {false, true}) {
            CAPTURE(weather);
            CAPTURE(engine);
            // the street's sections with the water on one side: its sample between the renders' half of the way and
            // the way's edge on the land (`land`) and over the water (`wet`), and within the renders' half on each
            // side (`half`); the bridge's sections over the water: its two samples between the renders' half and the
            // way's edge (`deck`) and within the renders' half (`half`); each by the colour it is nearer, the
            // section's centreline's or its side's beyond the way (the plain ground's, the water's)
            struct Count {
                std::size_t sections = 0;
                std::size_t samples = 0;
                std::size_t on = 0;
            };
            Count land;
            Count wet;
            Count deck;
            Count half;
            double way = 0;
            for (const style::CameraState& camera : cameras) {
                style::CameraState c = camera;
                if (!engine) c.flags &= ~style::camera_flag::engine_mode;
                // the street, the bridge and the water's surface alone: no lines, quay walls, shadows or elements on
                // the water to tell the samples' colours by
                namespace part = style::camera_part;
                c.parts = part::ground_lines | part::setts | part::ground_text | part::quays | part::building_shadows |
                          part::park_shadows | part::cloud_shadows | part::reflections | part::ripple | part::glitter |
                          part::rings | part::glints | part::water_streaks;
                // each section: its samples (device px) and which count each goes to; the first its centreline's, the
                // next two its sides' references
                struct Section {
                    std::vector<std::array<double, 2>> at;
                    std::vector<Count*> to;
                    std::vector<std::size_t> ref;
                };
                std::vector<Section> sections;
                const auto across = [&](const passes::GroundFrame& decided, double density, WindowDraw&) {
                    for (const passes::DrawnTile& t : decided.tiles) {
                        const view::Camera& cam = *t.cam;
                        const geo::GroundTile& g = *t.ground;
                        std::vector<geo::P2> street;
                        std::vector<geo::P2> bridge;
                        for (const geo::Fill& f : g.fills) {
                            if (f.kind != geo::FillKind::road) continue;
                            const std::vector<geo::P2> line = centre_of(g, f);
                            if (line.size() != 2) continue;
                            // the canal's street and bridge: two points each, the street north, the bridge east
                            const bool north = std::abs(line[1].z - line[0].z) > std::abs(line[1].x - line[0].x);
                            const double length = pymath::hypot(line[1].x - line[0].x, line[1].z - line[0].z);
                            if (north ? std::abs(length - 611.0) > 20.0 : std::abs(length - 30.0) > 1.0) continue;
                            (f.y > 0.3 ? bridge : street) = line;
                            // the way's width: twice its ribbon's
                            const geo::P2& v = g.points[f.first];
                            const geo::P2& m = g.points[f.first + f.count - 1];
                            way = 2 * pymath::hypot(v.x - m.x, v.z - m.z);
                        }
                        if (street.empty() || bridge.empty()) continue;
                        for (const bool on_bridge : {false, true}) {
                            const std::vector<geo::P2>& line = on_bridge ? bridge : street;
                            const std::vector<geo::P2>& other = on_bridge ? street : bridge;
                            const geo::P2& a = line[0];
                            const geo::P2& b = line[1];
                            const double length = pymath::hypot(b.x - a.x, b.z - a.z);
                            const double ux = (b.x - a.x) / length;
                            const double uz = (b.z - a.z) / length;
                            const double y = on_bridge ? 0.45 : 0.05;
                            const auto ground = [&](double s, double o) {
                                return geo::P2{.x = a.x + (ux * s) - (uz * o), .z = a.z + (uz * s) + (ux * o)};
                            };
                            const double step = on_bridge ? 1.0 : 3.0;
                            const auto steps = static_cast<std::uint32_t>(std::ceil(length / step));
                            for (std::uint32_t j = 0; j < steps; ++j) {
                                const double s = step * static_cast<double>(j);
                                // between the renders' half (1.375 m) and the way's edge (2.75 m), within the half,
                                // and beyond the way
                                const double between = (way / 2) - 0.15;
                                const double within_half = (way / 4) - 0.375;
                                const double off = 6.0;
                                const std::array<double, 7> offsets{0.0,     -off,         off,        -between,
                                                                    between, -within_half, within_half};
                                Section section;
                                bool valid = true;
                                std::array<bool, 7> water{};
                                for (std::size_t i = 0; i < offsets.size() && valid; ++i) {
                                    const geo::P2 p = ground(s, offsets.at(i));
                                    water.at(i) = in_water(g, p.x, p.z);
                                    // clear of the other way and its widened reach
                                    valid = i == 0 || distance_to(other, p.x, p.z) > (way / 2) + 1.0;
                                    const view::Vec3 q = cam.cam({p.x, y, p.z});
                                    valid = valid && q.z > cam.near() && q.z < 300.0;
                                    const view::Vec2 sp = cam.pc(q);
                                    section.at.push_back({sp.x * density, sp.y * density});
                                }
                                if (!valid) continue;
                                if (on_bridge) {
                                    // over the water on both sides
                                    if (!(water[1] && water[2] && water[3] && water[4])) continue;
                                    section.to = {nullptr, nullptr, nullptr, &deck, &deck, &half, &half};
                                } else {
                                    // the water on one side only, and that side's sample between over it
                                    if (water[1] == water[2] || water[3] != water[1] || water[4] != water[2]) continue;
                                    section.to = {
                                        nullptr, nullptr, nullptr, water[3] ? &wet : &land, water[4] ? &wet : &land,
                                        &half,   &half};
                                }
                                section.ref = {0, 1, 2, 1, 2, 1, 2};
                                sections.push_back(std::move(section));
                            }
                        }
                    }
                };
                const auto drawn = draw_window(*session, path, c, across, weather ? test::sunny() : dry());
                ATLAS_REQUIRE(drawn.has_value(), "the window was not drawn");
                const gpu::Pixels& px = drawn->pixels;
                const auto colour = [&](const std::array<double, 2>& at) -> std::optional<std::array<int, 3>> {
                    if (at[0] < 0.0 || at[1] < 0.0 || at[0] >= static_cast<double>(px.width) ||
                        at[1] >= static_cast<double>(px.height)) {
                        return std::nullopt;
                    }
                    const std::size_t i =
                        (static_cast<std::size_t>(at[1]) * px.width) + static_cast<std::size_t>(at[0]);
                    return std::array<int, 3>{px.rgba[i * 4], px.rgba[(i * 4) + 1], px.rgba[(i * 4) + 2]};
                };
                const auto apart = [](const std::array<int, 3>& p, const std::array<int, 3>& q) {
                    return ((p[0] - q[0]) * (p[0] - q[0])) + ((p[1] - q[1]) * (p[1] - q[1])) +
                           ((p[2] - q[2]) * (p[2] - q[2]));
                };
                for (const Section& section : sections) {
                    std::vector<std::array<int, 3>> c3;
                    for (const std::array<double, 2>& at : section.at) {
                        if (const auto c1 = colour(at)) c3.push_back(*c1);
                    }
                    if (c3.size() != section.at.size()) continue;
                    if (apart(c3[0], c3[1]) < 400 || apart(c3[0], c3[2]) < 400) continue;
                    std::vector<Count*> counted;
                    for (std::size_t i = 3; i < c3.size(); ++i) {
                        Count& n = *section.to.at(i);
                        if (std::ranges::find(counted, &n) == counted.end()) {
                            ++n.sections;
                            counted.push_back(&n);
                        }
                        ++n.samples;
                        n.on += apart(c3[i], c3[0]) < apart(c3[i], c3.at(section.ref.at(i))) ? 1U : 0U;
                    }
                }
            }
            MESSAGE(std::format("{}, {}: the way {:.2f} m wide; between the renders' half of it and its edge {} of {} "
                                "samples (in {} sections) on the street on the land, {} of {} ({}) over the water, {} "
                                "of {} ({}) on the bridge over the water; within the renders' half {} of {}",
                                engine ? "engine mode" : "the app's map", weather ? "with weather" : "without", way,
                                land.on, land.samples, land.sections, wet.on, wet.samples, wet.sections, deck.on,
                                deck.samples, deck.sections, half.on, half.samples));
            CHECK(way == doctest::Approx(5.5).epsilon(0.01));
            CHECK(land.sections > 10);
            CHECK(wet.sections > 10);
            CHECK(deck.sections > 5);
            CHECK(land.on == (engine ? 0 : land.samples));
            CHECK(wet.on == 0);
            CHECK(deck.on == (engine ? 0 : deck.samples));
            CHECK(half.on == half.samples);
        }
    }
}

#endif
