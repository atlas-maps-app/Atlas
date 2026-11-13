// The buildings pass on the host's Vulkan (plan-vector D10, K10's L2 on lavapipe): every roof shape and a houseboat
// from a synthetic render pack (tests/building/fixture.hpp's tile, so CI draws them), and the Westerkerk home and the
// Main board from the bundled packs (ATLAS_TILES_DIR), drawn through the frame builder and the world with their sun
// bits (K8's, through set 1's sun_bits), every building's walls, cells and roofs at their true depth with the objects
// bit, their light dispatched per frame, against an exact CPU raster of what the pass's twin says it draws: each
// element's polygon, a cell's grown by its 0.15 px edge with round joins (the true Minkowski sum, not the vertex
// stage's outline), each sample ending with the nearest element there by the depth the vertex stage gives it (the
// twin's ElementDepth: an edged element's face plane, a plain one's corners over its fan, with their leads), sampled
// where the GPU samples; and under the painter switch, the latest element in the twin's painter order, as the renders
// paint. Over it the roofs' thin edges, as the objects' thin strokes draw them (each segment along an edge a shape's
// stroke follows, its pixels covered by the shader's area in float, blended at each sample where its depth, its face's
// carried past the edge with the strokes' lead, passes the sample's shape's; 8 bits after each blend), held within a
// level a blend where a stroke reaches (lavapipe blends 8-bit targets in fixed point). At one sample a pixel
// (configuration X1) every pixel must be the reference's colour, but where a sample lies
// within 0.02 px of a shape's boundary (the reference drawn 0.02 px larger or smaller ends that sample with another
// shape), or where the two nearest elements of different colours there lie within 2^-17 of their depth of each other
// (the rasteriser's rounding against the twin's; counted apart and bounded); at 4x every pixel's samples are averaged
// as the composite averages them. Pixels near a quad that snapping at rest made concave or crossed (a wall seen within
// a tenth of a pixel of edge-on, which the vertex stage outlines as a convex polygon; a roof's face, filled as two
// lobes), or within 0.25 px of an n-gon's
// boundary (whose triangles, cut in the plan, can stray from its snapped ring at rest), are counted apart and bounded.
// A synthetic pair of buildings the renders melt (a tall one whose sort point lies nearer the eye than a long low one
// in front of it) is drawn by true depth as its geometry says and by the painter switch as the renders paint it. A new
// moment, lit ahead on the pass's own thread (ENGINE.md gain 5), is held frame by frame to the frames drawn from
// scratch at each moment: every frame one moment whole, the new one from the frame that takes it on, in both frame
// slots, its light copied in and none of it lit on the render thread. With the user's palette's guesses off, a guessed
// roof's shape is drawn as the plain box in its place, held to the twin alike. At night the shop floors' glazing is
// lit where one of its building's places is open and dark where none is (the Westerkerk home at 21:30), held to the
// twin alike. The validation layer stays silent.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Composite.hpp"
#include "passes/House.hpp"
#include "passes/Walls.hpp"
#include "scene/Heaps.hpp"
#include "scene/House.hpp"
#include "scene/ItemTable.hpp"
#include "scene/Pick.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "style/Palette.hpp"
#include "style/Theme.hpp"
#include "sun/Buckets.hpp"
#include "tests/building/fixture.hpp"
#include "tests/fx/stroke_area.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"
#include "twin/Twin.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <format>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] std::string environment(const char* name) {
    const char* v = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before threads start
    return v == nullptr ? std::string() : std::string(v);
}

/// amsterdam.py's sunny moment of 1 October 2026, 16:00 (tests/style/ThemeTest.cpp's), its sun the port's bits.
[[nodiscard]] style::ThemeBlock sunny() {
    namespace flag = style::theme_flag;
    style::ThemeBlock b{};
    b.version = style::theme_block_version;
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
    b.sun_vector = {sun::bundled_sun.x, sun::bundled_sun.y, sun::bundled_sun.z};
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

/// A camera of the map: `distance` m from the target (lat, lon), bearing and map pitch in degrees, at rest, on a frame
/// of `w` × `h` px at density `d` (the engine's frame is w / d × h / d CSS px).
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
    c.flags = style::camera_flag::engine_mode;
    return c;
}

/// The Westerkerk home (amsterdam.py's main camera): 10 m west and 30 m north of the Westerkerk, yaw 52°, 38° down,
/// 430 m.
[[nodiscard]] style::CameraState home(std::uint32_t w, std::uint32_t h, double d) {
    const double kx = 111320 * pymath::cos(pymath::radians(52.3746));
    return camera(52.3746 - (-30.0) / 110540.0, 4.8840 + (-10.0) / kx, 52.0, 52.0, 430.0, w, h, d);
}

/// A point of the frame, device px.
struct Pt {
    double x;
    double y;
};

/// The signed distance from p to a polygon's boundary, negative where SVG's nonzero rule fills it (its winding number
/// about p is not 0).
[[nodiscard]] double signed_distance(Pt p, std::span<const Pt> poly) {
    double d = 1e300;
    int winding = 0;
    for (std::size_t i = 0; i < poly.size(); ++i) {
        const Pt a = poly[i];
        const Pt b = poly[(i + 1) % poly.size()];
        const double dx = b.x - a.x;
        const double dy = b.y - a.y;
        const double l2 = (dx * dx) + (dy * dy);
        double t = l2 > 0 ? (((p.x - a.x) * dx) + ((p.y - a.y) * dy)) / l2 : 0.0;
        t = std::clamp(t, 0.0, 1.0);
        const double qx = a.x + (dx * t) - p.x;
        const double qy = a.y + (dy * t) - p.y;
        d = std::min(d, std::sqrt((qx * qx) + (qy * qy)));
        const double c = (dx * (p.y - a.y)) - (dy * (p.x - a.x)); // p's side of a b
        if (a.y <= p.y && b.y > p.y && c > 0) ++winding;
        if (a.y > p.y && b.y <= p.y && c < 0) --winding;
    }
    return winding != 0 ? -d : d;
}

/// The twin's objects that are filled paths, in painter's order, each with its polygon in device px and its depth.
struct Shape {
    std::vector<Pt> poly;
    double stroke = 0; ///< a stroke's half width, device px (0: none)
    std::uint32_t colour = 0;
    /// A quad whose corners snapping made concave or crossed: the vertex stage outlines a stroked one as a convex
    /// polygon (a wall seen within a tenth of a pixel of edge-on), and fills a plain one as its two lobes about the
    /// crossing, each over its own corners' depths, so near it the GPU may differ from the exact shape (counted
    /// apart).
    bool sliver = false;
    /// An n-gon (five corners or more): the GPU fills the pieces stage T cut from its ring in the plan, which at
    /// rest (each corner snapped to a tenth of a pixel) can stray from the snapped ring by a fraction of a pixel along
    /// its boundary (counted apart).
    bool ngon = false;
    /// How the vertex stage gives it its depth: the shape it draws, and the triangles it draws (CSS px) with their
    /// corners' reciprocal depths, as the rasteriser interpolates them (triangles_of).
    twin::ElementDepth depth{};
    std::vector<std::array<std::array<double, 3>, 3>> triangles;
    /// How far its depths may stray from the twin's, a fraction of them: float's rounding, amplified for an edged
    /// shape by its face plane's cancellation at its outline's points (a face seen almost edge-on: its normal almost
    /// across the ray, so dot(n, ray) is a small difference of large terms).
    double slack = 0;
    /// A window's frame: the band between its polygon grown by `stroke` and its glass's polygon, which it leaves out.
    std::vector<Pt> hole;
    /// A window's glass: its paint and its path's box (CSS px), whose colour each pixel takes at its centre
    /// (window_glass_fs); `colour` is then its top's.
    bool glass = false;
    shaders::BuildingGlass paint{};
    float top = 0.0F;
    float bottom = 0.0F;
};

/// Whether a polygon's distinct corners turn both ways (concave, or crossed).
[[nodiscard]] bool turns_both_ways(std::span<const Pt> poly) {
    std::vector<Pt> u;
    for (const Pt& p : poly) {
        if (u.empty() || p.x != u.back().x || p.y != u.back().y) u.push_back(p);
    }
    if (u.size() > 1 && u.front().x == u.back().x && u.front().y == u.back().y) u.pop_back();
    if (u.size() < 4) return false;
    bool pos = false;
    bool neg = false;
    for (std::size_t i = 0; i < u.size(); ++i) {
        const Pt a = u[(i + u.size() - 1) % u.size()];
        const Pt b = u[i];
        const Pt c = u[(i + 1) % u.size()];
        const double z = ((b.x - a.x) * (c.y - b.y)) - ((b.y - a.y) * (c.x - b.x));
        pos = pos || z > 0;
        neg = neg || z < 0;
    }
    return pos && neg;
}

/// A point the vertex stage draws (CSS px) with its reciprocal depth.
using Vertex = std::array<double, 3>;

/// The triangles the vertex stage draws an element as, each corner with its reciprocal depth (Shape::triangles): an
/// edged element's outline fan `half` its stroke's width out, 14 triangles from its first point, each point its face
/// plane's within the shape's span; a plain one's fan of 3 over its corners (a kit's band's or corner board's on its
/// wall's plane: building_fan_vertex's); an n-gon's pieces' fans, each over its own corners.
[[nodiscard]] std::vector<std::array<Vertex, 3>> triangles_of(const shaders::FrameCamera& cam,
                                                              const twin::ElementDepth& e, float half) {
    namespace sh = atlas::shaders;
    std::vector<Vertex> fan;
    std::vector<std::array<Vertex, 3>> out;
    const auto plain_fan = [&](const sh::BuildingShape& s) {
        fan.clear();
        for (std::uint32_t v = 0; v < sh::BUILDING_PLAIN_VERTICES; ++v) {
            const hlsl::float3 p = sh::building_fan_vertex(cam, s, v);
            fan.push_back({static_cast<double>(p.x), static_cast<double>(p.y), static_cast<double>(p.z)});
        }
        for (std::size_t t = 0; t + 2 < fan.size(); ++t) out.push_back({fan[0], fan[t + 1], fan[t + 2]});
    };
    if (e.edged) {
        for (std::uint32_t v = 0; v < sh::BUILDING_EDGED_VERTICES; ++v) {
            const hlsl::float2 o = sh::building_outline(e.shape, v, half);
            const float r = sh::building_plane_recip(cam, e.shape, o);
            fan.push_back({static_cast<double>(o.x), static_cast<double>(o.y), static_cast<double>(r)});
        }
        for (std::size_t t = 0; t + 2 < fan.size(); ++t) out.push_back({fan[0], fan[t + 1], fan[t + 2]});
    } else if (!e.pieces.empty()) {
        for (const sh::BuildingShape& piece : e.pieces) plain_fan(piece);
    } else {
        plain_fan(e.shape);
    }
    return out;
}

/// A shape's slack (Shape::slack): 2^-18 (the rasteriser's rounding of a long thin triangle's depth, against the
/// twin's: lavapipe's strays some twenty units of the last place), and for an edged one (or one on its wall's plane)
/// 2^-18 times its face plane's condition at its outline's points (the sum of dot(n, ray)'s terms' magnitudes over its
/// own).
[[nodiscard]] double slack_of(const shaders::FrameCamera& cam, const twin::ElementDepth& e,
                              std::span<const std::array<Vertex, 3>> triangles) {
    constexpr double base = 3.814697265625e-6; // 2^-18
    if (!e.edged && !e.shape.on_plane) return base;
    const auto f = static_cast<double>(cam.forward.w);
    const auto n = std::array<double, 3>{static_cast<double>(e.shape.plane.x), static_cast<double>(e.shape.plane.y),
                                         static_cast<double>(e.shape.plane.z)};
    double worst = 1.0;
    for (const std::array<Vertex, 3>& t : triangles) {
        const Vertex& p = t[1];
        const std::array<double, 3> ray{(p[0] - static_cast<double>(cam.right.w)) / f,
                                        (static_cast<double>(cam.up.w) - p[1]) / f, 1.0};
        const double terms = std::abs(n[0] * ray[0]) + std::abs(n[1] * ray[1]) + std::abs(n[2] * ray[2]);
        const double sum = std::abs((n[0] * ray[0]) + (n[1] * ray[1]) + (n[2] * ray[2]));
        worst = std::max(worst, sum > 0.0 ? terms / sum : 1e9);
    }
    return base * worst;
}

/// A colour of 0 to 1 channels as its levels, 0xRRGGBB (a UNORM8 attachment's rounding).
[[nodiscard]] std::uint32_t levels_of(hlsl::float3 c) noexcept {
    const auto level = [](float v) {
        return static_cast<std::uint32_t>(std::clamp(std::lround(static_cast<double>(v) * 255.0), 0L, 255L));
    };
    return (level(c.x) << 16U) | (level(c.y) << 8U) | level(c.z);
}

/// A wall's windows' path as the frame draws it (shaders/building/draw.slang's window_glass_vs and window_frames_vs):
/// each window its glass, the window eroded by the frames' half width, on its wall's plane a lead over the cells, its
/// colour its paint's at each pixel's centre; then its frame, the band between its outline grown by the half width with
/// round joins (the true Minkowski sum) and its glass, in the frames' colour a lead over the glass; at the path's
/// painter slot under the switch (the frame the later).
void add_windows(std::vector<Shape>& out, const twin::WindowsDepth& wd, double density, const shaders::FrameCamera& cam,
                 std::uint32_t tile) {
    namespace sh = atlas::shaders;
    for (const sh::BuildingWindowShape& s : wd.windows) {
        const auto depth_of = [&](float lead) {
            twin::ElementDepth d{.edged = true, .tile = tile};
            d.shape = sh::building_window_as_shape(s, wd.plane, lead);
            d.shape.slot = wd.slot;
            d.shape.depth = sh::painter_depth(cam, wd.slot);
            return d;
        };
        Shape frame;
        for (std::uint32_t k = 0; k < s.count; ++k) {
            const hlsl::float2 v = sh::building_window_corner(s, k);
            frame.poly.push_back({static_cast<double>(v.x) * density, static_cast<double>(v.y) * density});
        }
        frame.stroke = static_cast<double>(wd.half) * density;
        frame.colour = levels_of(wd.frame);
        frame.sliver = turns_both_ways(frame.poly);
        frame.depth = depth_of(wd.shop ? sh::BUILDING_GLAZING_FRAME_LEAD : sh::BUILDING_WINDOW_LEAD);
        // the frame's triangles as window_frames_vs draws them (its band's, or its box's), each point at
        // its wall's plane's reciprocal depth within the window's span: where that plane is ill-conditioned (a wall
        // seen edge-on) the clamped depths are not affine, so only the vertex stage's own triangles give its depths
        for (std::uint32_t v = 0; v < sh::BUILDING_WINDOW_FRAME_VERTICES; v += 3) {
            std::array<Vertex, 3> tri{};
            for (std::uint32_t k = 0; k < 3; ++k) {
                const hlsl::float2 c =
                    sh::building_window_frame_point(s, v + k, wd.half, sh::building_glass_drawn(s, wd.half));
                const float r = sh::building_plane_recip(cam, frame.depth.shape, c);
                tri[k] = {static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)};
            }
            frame.triangles.push_back(tri);
        }
        frame.slack = slack_of(cam, frame.depth, frame.triangles);
        if (!sh::building_glass_drawn(s, wd.half)) {
            out.push_back(std::move(frame));
            continue;
        }
        Shape glass;
        glass.depth = depth_of(wd.shop ? sh::BUILDING_GLAZING_GLASS_LEAD : sh::BUILDING_GLASS_LEAD);
        std::vector<Vertex> fan;
        for (std::uint32_t v = 0; v < sh::BUILDING_WINDOW_GLASS_VERTICES; ++v) {
            const hlsl::float2 c = sh::building_glass_corner(s, v < s.count ? v : s.count - 1, wd.half);
            if (v < s.count)
                glass.poly.push_back({static_cast<double>(c.x) * density, static_cast<double>(c.y) * density});
            const float r = sh::building_plane_recip(cam, glass.depth.shape, c);
            fan.push_back({static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)});
        }
        for (std::size_t k = 0; k + 2 < fan.size(); ++k) glass.triangles.push_back({fan[0], fan[k + 1], fan[k + 2]});
        glass.slack = slack_of(cam, glass.depth, glass.triangles);
        glass.glass = true;
        glass.paint = wd.glass;
        glass.top = wd.top;
        glass.bottom = wd.bottom;
        glass.colour = levels_of(wd.glass.top);
        frame.hole = glass.poly;
        out.push_back(std::move(glass));
        out.push_back(std::move(frame));
    }
}

/// A wall's windows' part as the frame draws it (shaders/building/draw.slang's window_near_vs): each shape (a deep
/// window's reveal or sill, or a sash bar's quad) filled in the part's colour on its wall's plane at its lead, a fan
/// from its first corner (the points past its last repeating it), each point its wall's plane's within the shape's
/// span; at the path's painter slot under the switch.
void add_parts(std::vector<Shape>& out, const twin::PartsDepth& pd, double density, const shaders::FrameCamera& cam,
               std::uint32_t tile) {
    namespace sh = atlas::shaders;
    for (const sh::BuildingWindowShape& s : pd.shapes) {
        Shape part;
        for (std::uint32_t k = 0; k < s.count; ++k) {
            const hlsl::float2 v = sh::building_window_corner(s, k);
            part.poly.push_back({static_cast<double>(v.x) * density, static_cast<double>(v.y) * density});
        }
        part.colour = levels_of(pd.colour);
        part.sliver = turns_both_ways(part.poly);
        part.depth = {.edged = true, .tile = tile};
        part.depth.shape = sh::building_window_as_shape(s, pd.plane, pd.lead);
        part.depth.shape.slot = pd.slot;
        part.depth.shape.depth = sh::painter_depth(cam, pd.slot);
        std::vector<Vertex> fan;
        for (std::uint32_t v = 0; v < sh::BUILDING_WINDOW_GLASS_VERTICES; ++v) {
            const hlsl::float2 c = sh::building_window_corner(s, v < s.count ? v : s.count - 1);
            const float r = sh::building_plane_recip(cam, part.depth.shape, c);
            fan.push_back({static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)});
        }
        for (std::size_t k = 0; k + 2 < fan.size(); ++k) part.triangles.push_back({fan[0], fan[k + 1], fan[k + 2]});
        part.slack = slack_of(cam, part.depth, part.triangles);
        out.push_back(std::move(part));
    }
}

/// A church wall's windows' part as the frame draws it (shaders/building/draw.slang's church_window_vs): each arch a
/// fan of its corners from its first, each bar of its leading a quad, filled in the part's paint (the glass its
/// gradient at each pixel's centre) on its wall's plane at its lead, each point its wall's plane's within the shape's
/// span; at the path's painter slot under the switch.
void add_arches(std::vector<Shape>& out, const twin::ArchesDepth& ad, double density, const shaders::FrameCamera& cam,
                std::uint32_t tile) {
    namespace sh = atlas::shaders;
    const auto add = [&](std::span<const hlsl::float2> corners, float lo, float hi) {
        Shape part;
        for (const hlsl::float2 v : corners) {
            part.poly.push_back({static_cast<double>(v.x) * density, static_cast<double>(v.y) * density});
        }
        part.colour = levels_of(ad.paint.top);
        part.glass = ad.paint.gradient;
        part.paint = ad.paint;
        part.top = ad.top;
        part.bottom = ad.bottom;
        part.sliver = turns_both_ways(part.poly);
        part.depth = {.edged = true, .tile = tile};
        sh::BuildingWindowShape span = sh::building_window_nothing();
        span.count = 3;
        span.lo = lo;
        span.hi = hi;
        part.depth.shape = sh::building_window_as_shape(span, ad.plane, ad.lead);
        part.depth.shape.slot = ad.slot;
        part.depth.shape.depth = sh::painter_depth(cam, ad.slot);
        std::vector<Vertex> fan;
        for (const hlsl::float2 c : corners) {
            const float r = sh::building_plane_recip(cam, part.depth.shape, c);
            fan.push_back({static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)});
        }
        for (std::size_t k = 0; k + 2 < fan.size(); ++k) part.triangles.push_back({fan[0], fan[k + 1], fan[k + 2]});
        part.slack = slack_of(cam, part.depth, part.triangles);
        out.push_back(std::move(part));
    };
    std::vector<hlsl::float2> corners;
    for (const sh::BuildingArch& a : ad.arches) {
        corners.clear();
        for (std::uint32_t k = 0; k < a.count; ++k) corners.push_back(a.p[k]);
        add(corners, a.lo, a.hi);
    }
    for (const sh::BuildingWindowShape& b : ad.bars) {
        corners.clear();
        for (std::uint32_t k = 0; k < b.count; ++k) corners.push_back(sh::building_window_corner(b, k));
        add(corners, b.lo, b.hi);
    }
}

/// The colour a sample of pixel (x, y) takes from shape `sh`: its colour, or a window's glass's at the pixel's centre
/// (its CSS point as window_glass_fs finds it, css_of_output's).
[[nodiscard]] std::uint32_t colour_at(const Shape& sh, std::int64_t x, std::int64_t y,
                                      const shaders::FrameCamera& cam) noexcept {
    if (!sh.glass) return sh.colour;
    const hlsl::float2 centre = {static_cast<float>(x) + 0.5F, static_cast<float>(y) + 0.5F};
    const float css = shaders::css_of_output(cam, centre).y;
    return levels_of(atlas::shaders::building_glass_at(sh.paint, sh.top, sh.bottom, css));
}

/// The document's shapes, each with its depth from `depths` (the pass's, in the stream's order) at frame camera `cam`,
/// a wall's stroke `wall` CSS px wide (the frame's strokes'); a wall's windows' path its windows' frames and glass, its
/// reveals', sash bars' and sills' paths their shapes.
[[nodiscard]] std::vector<Shape> shapes_of(const twin::Document& doc, double density,
                                           std::span<const twin::ElementDepth> depths, const shaders::FrameCamera& cam,
                                           float wall) {
    std::vector<Shape> out;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path) continue;
        if (e->sequence < depths.size() && !depths[e->sequence].windows.empty()) {
            add_windows(out, depths[e->sequence].windows.front(), density, cam, depths[e->sequence].tile);
            continue;
        }
        if (e->sequence < depths.size() && !depths[e->sequence].parts.empty()) {
            for (const twin::PartsDepth& pd : depths[e->sequence].parts) {
                add_parts(out, pd, density, cam, depths[e->sequence].tile);
            }
            continue;
        }
        // a church wall's windows' parts; their glass's veil is folded into their glass
        if (e->sequence < depths.size() && depths[e->sequence].veil) continue;
        // a wall's material face, blended over the raster (materials_of); a kit roof's lines (roof_lines_of); a kit
        // wall's lines and eaves' shadow (kit_walls_of)
        if (e->sequence < depths.size() && !depths[e->sequence].materials.empty()) continue;
        if (e->sequence < depths.size() && !depths[e->sequence].roof_lines.empty()) continue;
        if (e->sequence < depths.size() && !depths[e->sequence].kit_walls.empty()) continue;
        // a hoist beam: its segment grown by half its width (its round caps), its quad's depths along it as beam_vs
        // gives them
        if (e->sequence < depths.size() && !depths[e->sequence].beams.empty()) {
            const shaders::BuildingBeam& bb = depths[e->sequence].beams.front();
            Shape sh;
            sh.poly = {{static_cast<double>(bb.p0.x) * density, static_cast<double>(bb.p0.y) * density},
                       {static_cast<double>(bb.p1.x) * density, static_cast<double>(bb.p1.y) * density}};
            sh.stroke = static_cast<double>(bb.half) * density;
            sh.colour = levels_of(shaders::building_rgb(shaders::BUILDING_BEAM_RGB));
            // no face plane: its depth is its ends' along it, with the rasteriser's plain slack
            sh.depth = twin::ElementDepth{.edged = false, .tile = depths[e->sequence].tile};
            sh.depth.shape.lead = shaders::BUILDING_SILL_LEAD;
            sh.depth.shape.slot = bb.slot;
            sh.depth.shape.depth = shaders::painter_depth(cam, bb.slot);
            std::array<Vertex, 4> quad{};
            for (std::uint32_t k = 0; k < 4; ++k) {
                const hlsl::float2 c = shaders::building_beam_corner(bb, k);
                quad[k] = {static_cast<double>(c.x), static_cast<double>(c.y),
                           static_cast<double>(shaders::building_beam_recip(bb, c))};
            }
            sh.triangles = {{quad[0], quad[1], quad[2]}, {quad[0], quad[2], quad[3]}};
            sh.slack = slack_of(cam, sh.depth, sh.triangles);
            out.push_back(std::move(sh));
            continue;
        }
        if (e->sequence < depths.size() && !depths[e->sequence].arches.empty()) {
            for (const twin::ArchesDepth& ad : depths[e->sequence].arches) {
                add_arches(out, ad, density, cam, depths[e->sequence].tile);
            }
            continue;
        }
        if (e->points.size() < 3) continue;
        Shape sh;
        for (const twin::Point& p : e->points) {
            sh.poly.push_back({static_cast<double>(p.x) * density, static_cast<double>(p.y) * density});
        }
        // the cells' same-colour edges, which the pass draws; the roofs' thin edge strokes (.25, .45 and .4 px in other
        // colours) are drawn by the strokes' own draws and rastered apart
        const bool stroked =
            e->style.stroke.kind == twin::Paint::Kind::colour && std::abs(e->style.stroke_width - wall) < 1e-6F;
        sh.stroke = stroked ? static_cast<double>(e->style.stroke_width) * density / 2 : 0.0;
        sh.colour = e->style.fill.argb & 0xFF'FFFFU;
        sh.sliver = (stroked || sh.poly.size() == 4) && turns_both_ways(sh.poly);
        sh.ngon = !stroked && sh.poly.size() >= 5;
        if (e->sequence < depths.size()) {
            sh.depth = depths[e->sequence];
            sh.triangles = triangles_of(cam, sh.depth, 0.5F * wall);
            sh.slack = slack_of(cam, sh.depth, sh.triangles);
        }
        out.push_back(std::move(sh));
    }
    return out;
}

/// The depth the vertex stage gives a shape at CSS point (x, y), as the rasteriser interpolates it: its corners'
/// reciprocal depths over the triangle of its the point lies in (outside them, the one it lies least outside,
/// extended); then its lead, or its painter depth under the switch. `inside`, when given, gets how far inside that
/// triangle the point lies (its least barycentric weight).
[[nodiscard]] double depth_at(const shaders::FrameCamera& cam, const Shape& sh, double x, double y,
                              double* inside = nullptr) {
    double best = -1e300;
    double recip = sh.triangles.empty() ? 0.0 : sh.triangles[0][0][2];
    for (const std::array<Vertex, 3>& t : sh.triangles) {
        const Vertex& a = t[0];
        const Vertex& b = t[1];
        const Vertex& c = t[2];
        const double det = ((b[0] - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (b[1] - a[1]));
        if (std::abs(det) < 1e-9) continue;
        const double l1 = (((x - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (y - a[1]))) / det;
        const double l2 = (((b[0] - a[0]) * (y - a[1])) - ((x - a[0]) * (b[1] - a[1]))) / det;
        const double l0 = 1.0 - l1 - l2;
        const double in = std::min({l0, l1, l2});
        if (in > best) {
            best = in;
            recip = (l0 * a[2]) + (l1 * b[2]) + (l2 * c[2]);
        }
    }
    if (inside != nullptr) *inside = best;
    return static_cast<double>(shaders::building_depth(cam, sh.depth.shape, static_cast<float>(recip)));
}

/// No shape covers a sample.
constexpr std::int32_t uncovered = -1;

/// Two depths (reversed) a sample cannot tell apart: within the two shapes' slack of the nearer's (the rasteriser's
/// rounding of each against the twin's).
[[nodiscard]] bool tied(float nearer, float farther, double slack) noexcept {
    return static_cast<double>(nearer - farther) <= static_cast<double>(nearer) * slack;
}

/// What a raster gives each sample: the shape it ends with, or `uncovered`, and that shape's depth there (by true
/// depth); and, from a raster asked for them, whether the two nearest shapes of different colours there are tied in
/// depth.
struct Raster {
    std::vector<std::int32_t> shape;
    std::vector<float> depth;
    std::vector<std::uint8_t> tie;
};

/// The shapes rasterised at sample positions, each grown by `grow` px (its fill's polygon, a stroke's half width
/// added): the shape each sample ends with, the nearest by its depth at `cam` (the frame's camera; samples are device
/// px, CSS px times `density`), or under the painter switch (the camera's CAMERA_PAINTER) the latest in painter order;
/// with `ties`, whether the sample's nearest shape and the nearest of another colour are tied. With `slivers`, instead
/// every sample within a pixel of a sliver's shape, or within 0.25 px of an n-gon's boundary.
[[nodiscard]] Raster raster(std::span<const Shape> shapes, std::uint32_t w, std::uint32_t h,
                            std::span<const Pt> samples, double grow, const shaders::FrameCamera& cam, double density,
                            bool ties = false, bool slivers = false) {
    const bool painter = (cam.flags.x & shaders::CAMERA_PAINTER) != 0U;
    const std::size_t n = std::size_t{w} * h * samples.size();
    Raster out{.shape = std::vector<std::int32_t>(n, uncovered), .tie = {}};
    std::vector<float> nearest(n, 0.0F);
    std::vector<float> other; // the nearest depth of another colour than the nearest shape's, and its shape
    std::vector<std::int32_t> other_shape;
    if (ties) {
        other.assign(n, -1.0F);
        other_shape.assign(n, uncovered);
        out.tie.assign(n, 0);
    }
    for (std::size_t k = 0; k < shapes.size(); ++k) {
        const Shape& sh = shapes[k];
        if (slivers && !sh.sliver && !sh.ngon) continue;
        double x0 = 1e300;
        double y0 = 1e300;
        double x1 = -1e300;
        double y1 = -1e300;
        for (const Pt& q : sh.poly) {
            x0 = std::min(x0, q.x);
            y0 = std::min(y0, q.y);
            x1 = std::max(x1, q.x);
            y1 = std::max(y1, q.y);
        }
        const double reach = sh.stroke + grow + (slivers ? 1.0 : 0.0);
        const auto lo_x = static_cast<std::int64_t>(std::floor(x0 - reach - 1));
        const auto hi_x = static_cast<std::int64_t>(std::ceil(x1 + reach + 1));
        const auto lo_y = static_cast<std::int64_t>(std::floor(y0 - reach - 1));
        const auto hi_y = static_cast<std::int64_t>(std::ceil(y1 + reach + 1));
        for (std::int64_t y = std::max<std::int64_t>(0, lo_y); y < std::min<std::int64_t>(h, hi_y); ++y) {
            for (std::int64_t x = std::max<std::int64_t>(0, lo_x); x < std::min<std::int64_t>(w, hi_x); ++x) {
                for (std::size_t s = 0; s < samples.size(); ++s) {
                    const Pt p{.x = static_cast<double>(x) + samples[s].x, .y = static_cast<double>(y) + samples[s].y};
                    const double d = signed_distance(p, sh.poly);
                    const bool in = slivers && !sh.sliver ? std::abs(d) <= 0.25 : d <= reach;
                    if (!in) continue;
                    // a window's frame leaves its glass out (shrunk as the frame grows)
                    if (!slivers && sh.hole.size() >= 3 && signed_distance(p, sh.hole) < -grow) continue;
                    const std::size_t i =
                        (((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * samples.size()) + s;
                    if (painter || slivers) {
                        out.shape[i] = static_cast<std::int32_t>(k);
                        continue;
                    }
                    const auto z = static_cast<float>(depth_at(cam, sh, p.x / density, p.y / density));
                    const std::int32_t was = out.shape[i];
                    const bool same = was != uncovered && shapes[static_cast<std::size_t>(was)].colour == sh.colour;
                    if (was == uncovered || z > nearest[i]) {
                        if (ties && was != uncovered && !same) {
                            other[i] = nearest[i];
                            other_shape[i] = was;
                        }
                        out.shape[i] = static_cast<std::int32_t>(k);
                        nearest[i] = z;
                    } else if (ties && !same && z > other[i]) {
                        other[i] = z;
                        other_shape[i] = static_cast<std::int32_t>(k);
                    }
                }
            }
        }
    }
    if (ties) {
        for (std::size_t i = 0; i < n; ++i) {
            if (out.shape[i] == uncovered || other_shape[i] == uncovered) continue;
            const double slack = shapes[static_cast<std::size_t>(out.shape[i])].slack +
                                 shapes[static_cast<std::size_t>(other_shape[i])].slack;
            out.tie[i] = tied(nearest[i], other[i], slack) ? 1 : 0;
        }
    }
    out.depth = std::move(nearest);
    return out;
}

/// The thin strokes' margin around their rectangles, output px (strokes.slang's STROKE_MARGIN).
constexpr float stroke_margin = 1.5F;

/// A roof's thin stroke's segment as the frame draws it (shaders/building/draw.slang's roof_strokes_vs, through
/// strokes.slang's stroke_quad): its ends in device px as the vertex stage gives them, half its width there, its
/// colour, its quad's corners (device px) with the depth the vertex stage gives each (its face's reciprocal depth,
/// affine over the corners it was cut from, with the strokes' lead; under the painter switch its face's slot's), and
/// its face's painter slot.
struct StrokeSeg {
    float ax = 0;
    float ay = 0;
    float bx = 0;
    float by = 0;
    float hw = 0;
    std::uint32_t colour = 0;
    std::array<std::array<float, 2>, 4> quad{};
    std::array<float, 4> depth{};
    std::uint32_t slot = 0;

    /// Its depth at device point (x, y), as the rasteriser interpolates it over the quad's two triangles (stroke_quad's
    /// corners 0, 1, 2 and 2, 1, 3).
    [[nodiscard]] float depth_at(double x, double y) const {
        const auto in = [&](std::size_t i, std::size_t j, std::size_t k, double& v) {
            const auto px = static_cast<double>(quad[i][0]);
            const auto py = static_cast<double>(quad[i][1]);
            const auto qx = static_cast<double>(quad[j][0]);
            const auto qy = static_cast<double>(quad[j][1]);
            const auto rx = static_cast<double>(quad[k][0]);
            const auto ry = static_cast<double>(quad[k][1]);
            const double det = ((qx - px) * (ry - py)) - ((rx - px) * (qy - py));
            if (det == 0.0) return false;
            const double l1 = (((x - px) * (ry - py)) - ((rx - px) * (y - py))) / det;
            const double l2 = (((qx - px) * (y - py)) - ((x - px) * (qy - py))) / det;
            v = ((1.0 - l1 - l2) * static_cast<double>(depth[i])) + (l1 * static_cast<double>(depth[j])) +
                (l2 * static_cast<double>(depth[k]));
            return l1 >= 0.0 && l2 >= 0.0 && l1 + l2 <= 1.0;
        };
        double v = 0;
        if (in(0, 1, 2, v)) return static_cast<float>(v);
        (void)in(2, 1, 3, v);
        return static_cast<float>(v);
    }
};

/// The roofs' thin strokes of the document's elements in the order the frame draws them (the drawn tiles by their
/// distance from the eye, `distances` in the cover's order, the farthest first: the pass sorts them nearest first,
/// stably, and takes them backwards; each tile's elements as it emits them, its plain list's order): each stroked
/// plain element's segments along the edges its shape's stroke follows (an n-gon's, its triangles'), as the frame
/// draws them at frame camera `cam`.
[[nodiscard]] std::vector<StrokeSeg> strokes_of(const twin::Document& doc, std::span<const twin::ElementDepth> depths,
                                                std::span<const double> distances, const shaders::FrameCamera& cam) {
    namespace sh = atlas::shaders;
    std::vector<StrokeSeg> out;
    // output px from normalised coordinates (strokes.slang's stroke_output_scale: the tests' outputs are not turned)
    const hlsl::float2 scale = {cam.device.x, cam.device.y};
    const auto add = [&](const sh::BuildingShape& s, float width, std::uint32_t colour) {
        for (std::uint32_t k = 0; k < s.count; ++k) {
            if ((s.strokes & (1U << k)) == 0U) continue;
            const hlsl::float3 a = sh::building_corner_vertex(s, k);
            const hlsl::float3 b = sh::building_corner_vertex(s, k + 1 < s.count ? k + 1 : 0);
            const hlsl::float3 c = sh::building_corner_vertex(s, sh::building_stroke_third(s.count, k));
            const hlsl::float4 ca = sh::clip_of(cam, {a.x, a.y}, 1.0F, 0.0F);
            const hlsl::float4 cb = sh::clip_of(cam, {b.x, b.y}, 1.0F, 0.0F);
            const hlsl::float4 cc = sh::clip_of(cam, {c.x, c.y}, 1.0F, 0.0F);
            // stroke_quad's ends and corners
            const hlsl::float2 pa = {((ca.x / ca.w) + 1.0F) / scale.x, ((ca.y / ca.w) + 1.0F) / scale.y};
            const hlsl::float2 pb = {((cb.x / cb.w) + 1.0F) / scale.x, ((cb.y / cb.w) + 1.0F) / scale.y};
            const hlsl::float2 d = pb - pa;
            const float len = std::sqrt((d.x * d.x) + (d.y * d.y));
            if (!(len > 0.0F)) continue;
            const hlsl::float2 u = {d.x / len, d.y / len};
            const hlsl::float2 nrm = {-u.y, u.x};
            StrokeSeg g{.ax = pa.x,
                        .ay = pa.y,
                        .bx = pb.x,
                        .by = pb.y,
                        .hw = 0.5F * width * cam.frame.z,
                        .colour = colour,
                        .slot = s.slot};
            const hlsl::float3 oa = {pa.x, pa.y, a.z};
            const hlsl::float3 ob = {pb.x, pb.y, b.z};
            const hlsl::float3 oc = {((cc.x / cc.w) + 1.0F) / scale.x, ((cc.y / cc.w) + 1.0F) / scale.y, c.z};
            for (std::uint32_t q = 0; q < 4; ++q) {
                const float along = (q & 1U) != 0U ? len + stroke_margin : -stroke_margin;
                const float across = (q & 2U) != 0U ? g.hw + stroke_margin : -(g.hw + stroke_margin);
                const hlsl::float2 px = {pa.x + (u.x * along) + (nrm.x * across),
                                         pa.y + (u.y * along) + (nrm.y * across)};
                g.quad[q] = {px.x, px.y};
                // the vertex stage's corner in output px, back from its normalised coordinates
                const hlsl::float2 ndc = {(px.x * scale.x) - 1.0F, (px.y * scale.y) - 1.0F};
                const hlsl::float2 here = {(ndc.x + 1.0F) / scale.x, (ndc.y + 1.0F) / scale.y};
                g.depth[q] =
                    sh::building_stroke_depth(cam, s.slot, sh::building_stroke_recip(oa, ob, oc, here), s.lead);
            }
            out.push_back(g);
        }
    };
    // each cover tile's stroked elements, in the order they were emitted
    std::vector<std::vector<const twin::Element*>> by_tile(distances.size());
    std::vector<const twin::Element*> emitted(depths.size(), nullptr);
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->sequence >= depths.size()) continue;
        // a roof's thin stroke (a cell's same-colour 0.3 px edge is the walls' outline)
        if (e->style.stroke.kind != twin::Paint::Kind::colour || std::abs(e->style.stroke_width - 0.3F) < 1e-6F) {
            continue;
        }
        if (depths[e->sequence].edged || depths[e->sequence].tile >= distances.size()) continue;
        emitted[e->sequence] = e;
    }
    for (const twin::Element* e : emitted) {
        if (e != nullptr) by_tile[depths[e->sequence].tile].push_back(e);
    }
    std::vector<std::size_t> order(distances.size());
    for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::ranges::stable_sort(order, {}, [&](std::size_t i) { return distances[i]; });
    std::ranges::reverse(order);
    for (const std::size_t t : order) {
        for (const twin::Element* e : by_tile[t]) {
            const twin::ElementDepth& ed = depths[e->sequence];
            const std::uint32_t colour = e->style.stroke.argb & 0xFF'FFFFU;
            if (ed.pieces.empty()) {
                add(ed.shape, e->style.stroke_width, colour);
            } else {
                for (const sh::BuildingShape& piece : ed.pieces) add(piece, e->style.stroke_width, colour);
            }
        }
    }
    return out;
}

/// A wall's material face's path as the frame draws it (twin::MaterialDepth): its shapes (device px) each with its
/// depth as the vertex stage gives it, its paint (a glass wall's gradient over its fill's box) and opacity; a path of
/// lines blends each sample once.
struct MaterialPath {
    std::vector<Shape> shapes;
    bool lines = false;
    std::uint32_t slot = 0;
    shaders::BuildingGlass paint{};
    float top = 0.0F;
    float bottom = 0.0F;
    float opacity = 1.0F;
};

/// The document's material faces' paths as the frame draws them: every fill, then every line (material_fills, then
/// material_lines), each in the stream's order.
[[nodiscard]] std::vector<MaterialPath> materials_of(const twin::Document& doc, double density,
                                                     std::span<const twin::ElementDepth> depths,
                                                     const shaders::FrameCamera& cam) {
    namespace sh = atlas::shaders;
    std::vector<MaterialPath> fills;
    std::vector<MaterialPath> lines;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->sequence >= depths.size()) continue;
        for (const twin::MaterialDepth& md : depths[e->sequence].materials) {
            MaterialPath path{.shapes = {},
                              .lines = md.lines,
                              .slot = md.slot,
                              .paint = md.paint,
                              .top = md.top,
                              .bottom = md.bottom,
                              .opacity = md.opacity};
            for (const sh::BuildingWindowShape& s : md.shapes) {
                Shape shape;
                shape.depth = twin::ElementDepth{.edged = true, .tile = depths[e->sequence].tile};
                shape.depth.shape = sh::building_window_as_shape(s, md.plane, md.lead);
                shape.depth.shape.slot = md.slot;
                shape.depth.shape.depth = sh::painter_depth(cam, md.slot);
                // its fan from its first corner (a line's bar: its quad), each point at its wall's plane's reciprocal
                // depth within its span, as the vertex stage gives them
                std::vector<Vertex> fan;
                for (std::uint32_t k = 0; k < s.count; ++k) {
                    const hlsl::float2 c = sh::building_window_corner(s, k);
                    shape.poly.push_back({static_cast<double>(c.x) * density, static_cast<double>(c.y) * density});
                    const float r = sh::building_plane_recip(cam, shape.depth.shape, c);
                    fan.push_back({static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)});
                }
                for (std::size_t k = 0; k + 2 < fan.size(); ++k) {
                    shape.triangles.push_back({fan[0], fan[k + 1], fan[k + 2]});
                }
                shape.slack = slack_of(cam, shape.depth, shape.triangles);
                path.shapes.push_back(std::move(shape));
            }
            (md.lines ? lines : fills).push_back(std::move(path));
        }
    }
    for (MaterialPath& path : lines) fills.push_back(std::move(path));
    return fills;
}

/// A kit roof's line as the frame draws it (twin::RoofLinesDepth's): its quad's shape and its depth along it as
/// roof_line_vs gives them (a beam's), its segment and half its width in device px, its caps, colour and opacity, at
/// its slot; each line blended on its own.
struct RoofLine {
    Shape shape;
    Pt a{};
    Pt b{};
    double half = 0.0;
    bool round = false;
    std::uint32_t colour = 0;
    float opacity = 1.0F;
    std::uint32_t slot = 0;
};

/// The document's kit roofs' lines as the frame draws them, in the stream's order.
[[nodiscard]] std::vector<RoofLine> roof_lines_of(const twin::Document& doc, double density,
                                                  std::span<const twin::ElementDepth> depths,
                                                  const shaders::FrameCamera& cam) {
    std::vector<RoofLine> out;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->sequence >= depths.size()) continue;
        for (const twin::RoofLinesDepth& rd : depths[e->sequence].roof_lines) {
            for (const shaders::BuildingBeam& bb : rd.lines) {
                RoofLine l;
                l.shape.depth = twin::ElementDepth{.edged = false, .tile = depths[e->sequence].tile};
                l.shape.depth.shape.lead = shaders::BUILDING_ROOF_LINE_LEAD;
                l.shape.depth.shape.slot = bb.slot;
                l.shape.depth.shape.depth = shaders::painter_depth(cam, bb.slot);
                std::array<Vertex, 4> quad{};
                for (std::uint32_t k = 0; k < 4; ++k) {
                    const hlsl::float2 c = shaders::building_beam_corner(bb, k);
                    quad[k] = {static_cast<double>(c.x), static_cast<double>(c.y),
                               static_cast<double>(shaders::building_segment_recip(bb, c))};
                }
                l.shape.triangles = {{quad[0], quad[1], quad[2]}, {quad[0], quad[2], quad[3]}};
                l.shape.slack = slack_of(cam, l.shape.depth, l.shape.triangles);
                l.a = {.x = static_cast<double>(bb.p0.x) * density, .y = static_cast<double>(bb.p0.y) * density};
                l.b = {.x = static_cast<double>(bb.p1.x) * density, .y = static_cast<double>(bb.p1.y) * density};
                l.half = static_cast<double>(bb.half) * density;
                l.round = rd.round;
                l.colour = levels_of(shaders::building_rgb(rd.rgb));
                l.opacity = rd.opacity;
                l.slot = bb.slot;
                out.push_back(std::move(l));
            }
        }
    }
    return out;
}

/// The document's kit walls' lines and eaves' shadows as the frame draws them (roof_line_vs's on their walls' planes,
/// kit_shadow_vs's), each list in the stream's order: every line on its own, butt-capped; each shadow a path of one
/// shape on its wall's plane at the kit's shadow's lead, in its colour at its opacity.
struct KitWalls {
    std::vector<RoofLine> lines;
    std::vector<MaterialPath> shadows;
};

[[nodiscard]] KitWalls kit_walls_of(const twin::Document& doc, double density,
                                    std::span<const twin::ElementDepth> depths, const shaders::FrameCamera& cam) {
    namespace sh = atlas::shaders;
    KitWalls out;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->sequence >= depths.size()) continue;
        for (const twin::KitWallDepth& kd : depths[e->sequence].kit_walls) {
            for (const sh::BuildingRoofLine& r : kd.lines) {
                const sh::BuildingBeam& bb = r.line;
                RoofLine l;
                l.shape.depth = twin::ElementDepth{.edged = false, .tile = depths[e->sequence].tile};
                l.shape.depth.shape.lead = sh::building_roof_line_lead(r);
                l.shape.depth.shape.slot = kd.slot;
                l.shape.depth.shape.depth = sh::painter_depth(cam, kd.slot);
                std::array<Vertex, 4> quad{};
                for (std::uint32_t k = 0; k < 4; ++k) {
                    const hlsl::float2 c = sh::building_beam_corner(bb, k);
                    quad[k] = {static_cast<double>(c.x), static_cast<double>(c.y),
                               static_cast<double>(sh::building_roof_line_recip(cam, r, c))};
                }
                l.shape.triangles = {{quad[0], quad[1], quad[2]}, {quad[0], quad[2], quad[3]}};
                l.shape.slack = slack_of(cam, l.shape.depth, l.shape.triangles);
                l.a = {.x = static_cast<double>(bb.p0.x) * density, .y = static_cast<double>(bb.p0.y) * density};
                l.b = {.x = static_cast<double>(bb.p1.x) * density, .y = static_cast<double>(bb.p1.y) * density};
                l.half = static_cast<double>(bb.half) * density;
                l.round = false;
                l.colour = levels_of(sh::building_rgb(kd.rgb));
                l.opacity = kd.opacity;
                l.slot = kd.slot;
                out.lines.push_back(std::move(l));
            }
            if (kd.shadow.empty()) continue;
            const sh::BuildingWindowShape& s = kd.shadow.front();
            MaterialPath path{.shapes = {},
                              .lines = false,
                              .slot = kd.slot,
                              .paint = {},
                              .top = 0.0F,
                              .bottom = 0.0F,
                              .opacity = kd.opacity};
            path.paint.top = sh::building_rgb(kd.rgb);
            path.paint.bottom = path.paint.top;
            path.paint.gradient = false;
            Shape shape;
            shape.depth = twin::ElementDepth{.edged = true, .tile = depths[e->sequence].tile};
            shape.depth.shape = sh::building_window_as_shape(s, kd.plane, sh::BUILDING_KIT_SHADOW_LEAD);
            shape.depth.shape.slot = kd.slot;
            shape.depth.shape.depth = sh::painter_depth(cam, kd.slot);
            // its fan from its first corner, each point at its wall's plane's reciprocal depth within its span
            std::vector<Vertex> fan;
            for (std::uint32_t k = 0; k < s.count; ++k) {
                const hlsl::float2 c = sh::building_window_corner(s, k);
                shape.poly.push_back({static_cast<double>(c.x) * density, static_cast<double>(c.y) * density});
                const float r = sh::building_plane_recip(cam, shape.depth.shape, c);
                fan.push_back({static_cast<double>(c.x), static_cast<double>(c.y), static_cast<double>(r)});
            }
            for (std::size_t k = 0; k + 2 < fan.size(); ++k)
                shape.triangles.push_back({fan[0], fan[k + 1], fan[k + 2]});
            shape.slack = slack_of(cam, shape.depth, shape.triangles);
            path.shapes.push_back(std::move(shape));
            out.shadows.push_back(std::move(path));
        }
    }
    return out;
}

/// What the strokes leave each sample of a raster: its colour (levels, rounded after each blend as the attachment
/// keeps them), how many strokes blended over it (at most 255), and whether a stroke's depth there was tied with the
/// sample's shape's.
struct Stroked {
    std::vector<std::array<std::uint8_t, 3>> colour;
    std::vector<std::uint8_t> blends;
    std::vector<std::uint8_t> tie;
};

/// The raster `r` of `shapes` (its samples at `samples` of each pixel of w × h) with a kit's walls' boards' lines
/// `kit_lines` blended over it first, each on its own as the roof lines below; then the material faces' paths
/// `materials` (a kit's walls' eaves' shadows the first), each at its samples where one of its shapes (grown by `grow`
/// device px) covers
/// it and its depth passes GREATER against the sample's shape's (by true depth, or under the painter switch by their
/// slots), at its opacity in its paint at the pixel's centre, a path of lines each sample once (the frame's stencil
/// bit); then the strokes `segs` blended over it in their order, as the frame's two pipelines of them blend
/// (structural mode: alpha a, the stroke's colour, over the ground and over the objects alike): each pixel's coverage
/// (the shader's, in float) at each of its samples where the stroke's depth passes GREATER_OR_EQUAL against the
/// sample's shape's, premultiplied source over, then 8 bits.
[[nodiscard]] Stroked stroke(const Raster& r, std::span<const Shape> shapes, std::span<const RoofLine> kit_lines,
                             std::span<const MaterialPath> materials, std::span<const StrokeSeg> segs,
                             std::span<const RoofLine> roof_lines, std::uint32_t w, std::uint32_t h,
                             std::span<const Pt> samples, const shaders::FrameCamera& cam, std::uint32_t clear,
                             double density, double grow) {
    const bool painter = (cam.flags.x & shaders::CAMERA_PAINTER) != 0U;
    const std::size_t n = std::size_t{w} * h * samples.size();
    Stroked out;
    out.colour.resize(n);
    out.blends.assign(n, 0);
    out.tie.assign(n, 0);
    for (std::size_t i = 0; i < n; ++i) {
        const std::size_t pixel = i / samples.size();
        const auto row = static_cast<std::int64_t>(pixel / w);
        const auto column = static_cast<std::int64_t>(pixel % w);
        const std::uint32_t c =
            r.shape[i] == uncovered ? clear : colour_at(shapes[static_cast<std::size_t>(r.shape[i])], column, row, cam);
        out.colour[i] = {static_cast<std::uint8_t>((c >> 16U) & 0xFFU), static_cast<std::uint8_t>((c >> 8U) & 0xFFU),
                         static_cast<std::uint8_t>(c & 0xFFU)};
    }
    constexpr double rounding = 3.814697265625e-6; // 2^-18: the rasteriser's rounding of the stroke's quad
    // lines each on its own (roof_line_fs): at each sample within half its width of its segment (round caps: the
    // ridge's; butt: within its length, the courses' and a kit wall's boards'), grown by `grow`, where its depth passes
    // GREATER against the sample's shape's, at its opacity
    const auto blend_lines = [&](std::span<const RoofLine> lines) {
        for (const RoofLine& l : lines) {
            const double reach = l.half + std::abs(grow) + 1.0;
            const auto x0 = static_cast<std::int64_t>(std::floor(std::min(l.a.x, l.b.x) - reach));
            const auto x1 = static_cast<std::int64_t>(std::ceil(std::max(l.a.x, l.b.x) + reach));
            const auto y0 = static_cast<std::int64_t>(std::floor(std::min(l.a.y, l.b.y) - reach));
            const auto y1 = static_cast<std::int64_t>(std::ceil(std::max(l.a.y, l.b.y) + reach));
            const double dx = l.b.x - l.a.x;
            const double dy = l.b.y - l.a.y;
            const double len = std::sqrt((dx * dx) + (dy * dy));
            const double ux = len > 0 ? dx / len : 1.0;
            const double uy = len > 0 ? dy / len : 0.0;
            const std::array<double, 3> level{static_cast<double>((l.colour >> 16U) & 0xFFU),
                                              static_cast<double>((l.colour >> 8U) & 0xFFU),
                                              static_cast<double>(l.colour & 0xFFU)};
            const auto a = static_cast<double>(l.opacity);
            for (std::int64_t y = std::max<std::int64_t>(0, y0); y <= std::min<std::int64_t>(h - 1, y1); ++y) {
                for (std::int64_t x = std::max<std::int64_t>(0, x0); x <= std::min<std::int64_t>(w - 1, x1); ++x) {
                    for (std::size_t s = 0; s < samples.size(); ++s) {
                        const Pt p{.x = static_cast<double>(x) + samples[s].x,
                                   .y = static_cast<double>(y) + samples[s].y};
                        const double along = ((p.x - l.a.x) * ux) + ((p.y - l.a.y) * uy);
                        bool covered = false;
                        if (l.round) {
                            const double t = std::clamp(along, 0.0, len);
                            const double ex = p.x - (l.a.x + (ux * t));
                            const double ey = p.y - (l.a.y + (uy * t));
                            covered = std::sqrt((ex * ex) + (ey * ey)) <= l.half + grow;
                        } else if (len > 0) {
                            const double across = ((p.y - l.a.y) * ux) - ((p.x - l.a.x) * uy);
                            covered = along >= -grow && along <= len + grow && std::abs(across) <= l.half + grow;
                        }
                        if (!covered) continue;
                        const std::size_t i =
                            (((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * samples.size()) + s;
                        const std::int32_t k = r.shape[i];
                        bool pass = k == uncovered;
                        if (!pass && painter) {
                            pass = l.slot > shapes[static_cast<std::size_t>(k)].depth.shape.slot;
                        } else if (!pass) {
                            const auto z = static_cast<float>(depth_at(cam, l.shape, p.x / density, p.y / density));
                            const float under = r.depth[i];
                            pass = z > under;
                            const double slack = shapes[static_cast<std::size_t>(k)].slack + l.shape.slack + rounding;
                            if (std::abs(static_cast<double>(z) - static_cast<double>(under)) <=
                                static_cast<double>(under) * slack) {
                                out.tie[i] = 1;
                            }
                        }
                        if (!pass) continue;
                        if (out.blends[i] < 255) ++out.blends[i];
                        std::array<std::uint8_t, 3>& dst = out.colour[i];
                        for (std::size_t q = 0; q < 3; ++q) {
                            const double v = (level[q] * a) + (static_cast<double>(dst[q]) * (1.0 - a));
                            dst[q] = static_cast<std::uint8_t>(std::clamp(std::round(v), 0.0, 255.0));
                        }
                    }
                }
            }
        }
    };
    // a kit wall's boards' lines first (the kit walls' phase), its eaves' shadows among the material faces' paths
    blend_lines(kit_lines);
    // the material faces: each path's samples once (a fill's shapes never meet; a sample takes one line of all)
    std::vector<std::uint8_t> lined(n, 0);
    std::vector<std::uint8_t> taken;
    for (const MaterialPath& path : materials) {
        taken.assign(n, 0);
        for (const Shape& sh : path.shapes) {
            double x0 = 1e300;
            double y0 = 1e300;
            double x1 = -1e300;
            double y1 = -1e300;
            for (const Pt& q : sh.poly) {
                x0 = std::min(x0, q.x);
                y0 = std::min(y0, q.y);
                x1 = std::max(x1, q.x);
                y1 = std::max(y1, q.y);
            }
            const auto lo_x = static_cast<std::int64_t>(std::floor(x0 - std::abs(grow) - 1));
            const auto hi_x = static_cast<std::int64_t>(std::ceil(x1 + std::abs(grow) + 1));
            const auto lo_y = static_cast<std::int64_t>(std::floor(y0 - std::abs(grow) - 1));
            const auto hi_y = static_cast<std::int64_t>(std::ceil(y1 + std::abs(grow) + 1));
            for (std::int64_t y = std::max<std::int64_t>(0, lo_y); y < std::min<std::int64_t>(h, hi_y); ++y) {
                for (std::int64_t x = std::max<std::int64_t>(0, lo_x); x < std::min<std::int64_t>(w, hi_x); ++x) {
                    for (std::size_t s = 0; s < samples.size(); ++s) {
                        const Pt p{.x = static_cast<double>(x) + samples[s].x,
                                   .y = static_cast<double>(y) + samples[s].y};
                        if (!(signed_distance(p, sh.poly) <= grow)) continue;
                        const std::size_t i =
                            (((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * samples.size()) + s;
                        if (taken[i] != 0 || (path.lines && lined[i] != 0)) continue;
                        const std::int32_t k = r.shape[i];
                        bool pass = k == uncovered;
                        if (!pass && painter) {
                            pass = path.slot > shapes[static_cast<std::size_t>(k)].depth.shape.slot;
                        } else if (!pass) {
                            const auto z = static_cast<float>(depth_at(cam, sh, p.x / density, p.y / density));
                            const float under = r.depth[i];
                            pass = z > under;
                            const double slack = shapes[static_cast<std::size_t>(k)].slack + sh.slack + rounding;
                            if (std::abs(static_cast<double>(z) - static_cast<double>(under)) <=
                                static_cast<double>(under) * slack) {
                                out.tie[i] = 1;
                            }
                        }
                        if (!pass) continue;
                        taken[i] = 1;
                        if (path.lines) lined[i] = 1;
                        if (out.blends[i] < 255) ++out.blends[i];
                        const hlsl::float2 centre = {static_cast<float>(x) + 0.5F, static_cast<float>(y) + 0.5F};
                        const std::uint32_t c = levels_of(shaders::building_glass_at(
                            path.paint, path.top, path.bottom, shaders::css_of_output(cam, centre).y));
                        const std::array<double, 3> level{static_cast<double>((c >> 16U) & 0xFFU),
                                                          static_cast<double>((c >> 8U) & 0xFFU),
                                                          static_cast<double>(c & 0xFFU)};
                        const auto a = static_cast<double>(path.opacity);
                        std::array<std::uint8_t, 3>& dst = out.colour[i];
                        for (std::size_t q = 0; q < 3; ++q) {
                            const double v = (level[q] * a) + (static_cast<double>(dst[q]) * (1.0 - a));
                            dst[q] = static_cast<std::uint8_t>(std::clamp(std::round(v), 0.0, 255.0));
                        }
                    }
                }
            }
        }
    }
    for (const StrokeSeg& g : segs) {
        const float reach = g.hw + 1.0F;
        const auto x0 = static_cast<std::int64_t>(std::floor(std::min(g.ax, g.bx) - reach));
        const auto x1 = static_cast<std::int64_t>(std::ceil(std::max(g.ax, g.bx) + reach));
        const auto y0 = static_cast<std::int64_t>(std::floor(std::min(g.ay, g.by) - reach));
        const auto y1 = static_cast<std::int64_t>(std::ceil(std::max(g.ay, g.by) + reach));
        const std::array<double, 3> level{static_cast<double>((g.colour >> 16U) & 0xFFU),
                                          static_cast<double>((g.colour >> 8U) & 0xFFU),
                                          static_cast<double>(g.colour & 0xFFU)};
        for (std::int64_t y = std::max<std::int64_t>(0, y0); y <= std::min<std::int64_t>(h - 1, y1); ++y) {
            for (std::int64_t x = std::max<std::int64_t>(0, x0); x <= std::min<std::int64_t>(w - 1, x1); ++x) {
                const float a = test::stroke_area(g.ax, g.ay, g.bx, g.by, g.hw, static_cast<std::uint32_t>(x),
                                                  static_cast<std::uint32_t>(y));
                if (!(a > 0.0F)) continue;
                for (std::size_t s = 0; s < samples.size(); ++s) {
                    const std::size_t i =
                        (((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * samples.size()) + s;
                    const std::int32_t k = r.shape[i];
                    bool pass = k == uncovered;
                    if (!pass && painter) {
                        pass = g.slot >= shapes[static_cast<std::size_t>(k)].depth.shape.slot;
                    } else if (!pass) {
                        const float z =
                            g.depth_at(static_cast<double>(x) + samples[s].x, static_cast<double>(y) + samples[s].y);
                        const float under = r.depth[i];
                        pass = z >= under;
                        const double slack = shapes[static_cast<std::size_t>(k)].slack + rounding;
                        if (std::abs(static_cast<double>(z) - static_cast<double>(under)) <=
                            static_cast<double>(under) * slack) {
                            out.tie[i] = 1;
                        }
                    }
                    if (!pass) continue;
                    if (out.blends[i] < 255) ++out.blends[i];
                    std::array<std::uint8_t, 3>& dst = out.colour[i];
                    for (std::size_t c = 0; c < 3; ++c) {
                        const double v = (level[c] * static_cast<double>(a)) +
                                         (static_cast<double>(dst[c]) * (1.0 - static_cast<double>(a)));
                        dst[c] = static_cast<std::uint8_t>(std::clamp(std::round(v), 0.0, 255.0));
                    }
                }
            }
        }
    }
    // a kit roof's tile courses and lit ridges after them
    blend_lines(roof_lines);
    return out;
}

/// The packs, the world, the builder and the renderer of one test frame.
struct Scene {
    std::unique_ptr<gpu::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Buildings* buildings = nullptr;
    passes::House* house = nullptr;
    double now = 0;
    bool settled = false; ///< every tile the cover wants built and faded in
    bool lit = false;     ///< every tile drawn with its own sun bits, their fade over
};

/// The distance from the eye of each tile the scene's cover draws, in the cover's order.
[[nodiscard]] std::vector<double> distances(const Scene& s) {
    std::vector<double> out;
    for (const scene::CoverTile& t : s.world->cover()->tiles()) out.push_back(t.distance);
    return out;
}

/// The user's palette the frames draw by (FrameInputs::palette; null: today's), set while a WithPalette lives.
// NOLINTNEXTLINE(cppcoreguidelines-avoid-non-const-global-variables): the test's own switch
const style::PaletteBlock* frame_palette = nullptr;

/// Draws the frames by `palette` while it lives (the switch cleared however a test ends).
class WithPalette {
public:
    explicit WithPalette(const style::PaletteBlock* palette) noexcept { frame_palette = palette; }
    ~WithPalette() { frame_palette = nullptr; }
    WithPalette(const WithPalette&) = delete;
    WithPalette& operator=(const WithPalette&) = delete;
    WithPalette(WithPalette&&) = delete;
    WithPalette& operator=(WithPalette&&) = delete;
};

/// Draws `cam` until the world has built every tile the cover wants and their fades are over, then until the sun has
/// settled: its workers idle, nothing published since the frame before and no tile's bits cross-fading. The sun's
/// fades run on its own steady clock, not on the frames' seconds (SunGlobals::now), so a frame drawn while one runs
/// differs from its still redraw (draw_still), which shows that fade further on. The last frame's pixels.
[[nodiscard]] std::optional<gpu::Pixels> draw(Scene& s, const style::CameraState& cam, const style::ThemeBlock& th) {
    s.builder->set_inputs({.camera = cam,
                           .theme = th,
                           .seconds = s.now,
                           .mode = frame::Mode::structural,
                           .scene = s.world.get(),
                           .palette = frame_palette});
    int waited = 0;
    for (int n = 0; n < 80; ++n) {
        s.now += 0.25;
        s.builder->set_inputs({.camera = cam,
                               .theme = th,
                               .seconds = s.now,
                               .mode = frame::Mode::structural,
                               .scene = s.world.get(),
                               .palette = frame_palette});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        s.world->residency().wait_idle();
        const scene::Cover& cover = *s.world->cover();
        // (a part drawn again: its top-ups taken and faded in, Cover::lacking and fading)
        const bool built = n > 4 && cover.missing() == 0 && !cover.tiles().empty() && cover.lacking() == 0 &&
                           !cover.fading() &&
                           std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
        // every tile drawn with its own sun bits, their fade over
        const bool lit = std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) {
            return t.sun_base != scene::no_sun && t.sun.has_value() && t.sun_weight >= 1.0F;
        });
        s.settled = built;
        s.lit = lit;
        if (built && lit) break;
        // the sun's bits, given twenty frames to come once the tiles are in
        const bool given_up = built && ++waited > 20;
        if (given_up || n == 79) {
            const scene::ResidencyStats st = s.world->residency().stats();
            const scene::SunStats sun = s.world->sun() != nullptr ? s.world->sun()->stats() : scene::SunStats{};
            const auto with_bits = std::ranges::count_if(
                cover.tiles(), [](const scene::CoverTile& t) { return t.sun_base != scene::no_sun; });
            MESSAGE(std::format("not settled or lit after {} frames: {} missing, {} pending, {} resident, {} built, {} "
                                "failed, {} released, {} tiles drawn, {} with sun bits; the sun: {} scenes ({} failed, "
                                "the last of {} tiles, {} resident, {:.0f} ms), {} runs",
                                n + 1, cover.missing(), st.pending, st.resident, st.built, st.failed, st.released,
                                cover.tiles().size(), with_bits, sun.scenes, sun.failed, sun.tiles, sun.resident,
                                sun.scene_ms, sun.runs));
            break;
        }
        if (built && s.world->sun() != nullptr) s.world->sun()->wait_idle();
    }
    // one more, with every tile taken and settled, and more until the sun has settled: bits the last frames asked for
    // (a halo tile come resident asks again) or a tile's first bits after a frame that drew it by day fade in over
    // sun::fade_seconds of its clock
    scene::SunGlobals* sun = s.world->sun();
    const auto sun_work = [sun] {
        const scene::SunStats st = sun != nullptr ? sun->stats() : scene::SunStats{};
        return std::array<std::uint64_t, 4>{st.scenes, st.failed, st.runs, st.cached};
    };
    for (int n = 0;; ++n) {
        const std::array<std::uint64_t, 4> before = sun_work();
        s.now += 0.25;
        s.builder->set_inputs({.camera = cam,
                               .theme = th,
                               .seconds = s.now,
                               .mode = frame::Mode::structural,
                               .scene = s.world.get(),
                               .palette = frame_palette});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        if (sun == nullptr) break;
        sun->wait_idle();
        if (!sun->fading() && sun_work() == before) break;
        if (n == 40) {
            MESSAGE("the sun not settled after 40 more frames");
            s.settled = false;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50)); // the fades' clock is the sun's own
    }
    s.lit = std::ranges::all_of(s.world->cover()->tiles(), [](const scene::CoverTile& t) {
        return t.sun_base != scene::no_sun && t.sun.has_value() && t.sun_weight >= 1.0F;
    });
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

/// Whether the last frame of `s` showed moment `th`.
[[nodiscard]] bool shows(const Scene& s, const style::ThemeBlock& th) {
    return std::ranges::equal(std::as_bytes(std::span(&s.builder->state().theme, 1)), std::as_bytes(std::span(&th, 1)));
}

/// Draws `cam` at moment `th` once the world has settled, until a frame shows it (the pass lights a new moment ahead on
/// a thread of its own, and the frame builder shows it once lit), then once more, in the other frame slot; the last
/// frame's pixels.
[[nodiscard]] std::optional<gpu::Pixels> redraw(Scene& s, const style::CameraState& cam, const style::ThemeBlock& th) {
    bool shown = false;
    for (int n = 0; n < 40; ++n) {
        s.now += 0.25;
        s.builder->set_inputs({.camera = cam,
                               .theme = th,
                               .seconds = s.now,
                               .mode = frame::Mode::structural,
                               .scene = s.world.get(),
                               .palette = frame_palette});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        if (shown) break;
        shown = shows(s, th);
        if (!shown) s.buildings->wait_ahead();
    }
    if (!shown) {
        FAIL("no frame showed the moment");
        return std::nullopt;
    }
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

/// Draws `cam` at moment `th` once more, its inputs the last frame's (no time passes); its pixels.
[[nodiscard]] std::optional<gpu::Pixels> draw_still(Scene& s, const style::CameraState& cam,
                                                    const style::ThemeBlock& th) {
    s.builder->set_inputs({.camera = cam,
                           .theme = th,
                           .seconds = s.now,
                           .mode = frame::Mode::structural,
                           .scene = s.world.get(),
                           .palette = frame_palette});
    auto report = s.renderer->draw(0);
    if (!report) {
        FAIL("a frame failed: " << report.error().text());
        return std::nullopt;
    }
    s.renderer->drain();
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

/// The standard sample positions of a configuration's sample count (Vulkan's, as every device reporting
/// standardSampleLocations uses).
[[nodiscard]] std::vector<Pt> sample_positions(vk::SampleCountFlagBits samples) {
    switch (samples) {
    case vk::SampleCountFlagBits::e4: return {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}};
    case vk::SampleCountFlagBits::e2: return {{0.75, 0.75}, {0.25, 0.25}};
    default: return {{0.5, 0.5}};
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the diagonal from its top-left corner to its bottom-right one: where
/// lavapipe, clipping the composite's full-screen triangle to the frame, joins the two triangles it draws it as, and a
/// resolving composite reads what its first fragment wrote (tests/frame/composite_vk_test.cpp's on_seam).
[[nodiscard]] bool on_seam(std::size_t x, std::size_t y, std::size_t w, std::size_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

/// What a frame's comparison with the exact raster found.
struct Compared {
    std::size_t pixels = 0;   ///< pixels some building covers in the reference
    std::size_t agree = 0;    ///< pixels equal to the reference
    std::size_t boundary = 0; ///< pixels that differ, a sample within the tolerance of a shape's boundary
    std::size_t tie = 0;      ///< pixels that differ, a sample's two nearest shapes of different colours tied in depth
    std::size_t sliver = 0;  ///< pixels that differ near a stroked quad snapping made concave or crossed, or an n-gon's
                             ///< boundary
    std::size_t seam = 0;    ///< pixels that differ on the composite's seam (a resolving composite on lavapipe)
    std::size_t wrong = 0;   ///< pixels that differ otherwise
    std::size_t slivers = 0; ///< such quads in the frame
    std::size_t ngons = 0;   ///< n-gons in the frame
    std::size_t segments = 0; ///< the roofs' thin strokes' segments the twin's elements give
    double quads = 0;         ///< the area their quads cover, full frames (the fragments their draws shade)
    std::size_t stroked = 0;  ///< pixels a stroke reaches in the reference (held to it within the blends' rounding)
    std::string first;
};

/// The GPU's frame `got` against the exact raster of the twin's document `doc`, whose elements' depths are `depths`,
/// drawn with frame block `block` (its camera and strokes) over cover tiles at `distances` from the eye.
[[nodiscard]] Compared compare(const gpu::Pixels& got, const twin::Document& doc,
                               std::span<const twin::ElementDepth> depths, std::span<const double> distances,
                               const shaders::FrameBlock& block, const gpu::Configuration& config, double density,
                               std::uint32_t clear) {
    const shaders::FrameCamera& cam = block.camera;
    const std::vector<Pt> samples = sample_positions(config.samples());
    const std::uint32_t w = got.width;
    const std::uint32_t h = got.height;
    constexpr double tolerance = 0.02;
    const std::vector<Shape> shapes = shapes_of(doc, density, depths, cam, block.strokes.widths.w);
    const Raster exact_raster = raster(shapes, w, h, samples, 0.0, cam, density, true);
    const std::vector<std::int32_t>& exact = exact_raster.shape;
    const Raster grown_raster = raster(shapes, w, h, samples, tolerance, cam, density);
    const Raster shrunk_raster = raster(shapes, w, h, samples, -tolerance, cam, density);
    const std::vector<std::int32_t>& grown = grown_raster.shape;
    const std::vector<std::int32_t>& shrunk = shrunk_raster.shape;
    const std::vector<std::int32_t> near_sliver = raster(shapes, w, h, samples, 0.0, cam, density, false, true).shape;
    // a kit's walls' lines and eaves' shadows, the material faces, then the roofs' thin strokes, over each of them
    KitWalls kits = kit_walls_of(doc, density, depths, cam);
    std::vector<MaterialPath> materials = std::move(kits.shadows);
    for (MaterialPath& m : materials_of(doc, density, depths, cam)) materials.push_back(std::move(m));
    const std::vector<StrokeSeg> segs = strokes_of(doc, depths, distances, cam);
    // and a kit roof's lines after them
    const std::vector<RoofLine> roof_lines = roof_lines_of(doc, density, depths, cam);
    const Stroked exact_stroked =
        stroke(exact_raster, shapes, kits.lines, materials, segs, roof_lines, w, h, samples, cam, clear, density, 0.0);
    const Stroked grown_stroked = stroke(grown_raster, shapes, kits.lines, materials, segs, roof_lines, w, h, samples,
                                         cam, clear, density, tolerance);
    const Stroked shrunk_stroked = stroke(shrunk_raster, shapes, kits.lines, materials, segs, roof_lines, w, h, samples,
                                          cam, clear, density, -tolerance);
    const std::size_t n = samples.size();
    const auto resolved = [&](const Stroked& r, std::size_t i) {
        // the composite's mean of the samples' levels, rounded half to even (passes/Composite.hpp's composite_pixel)
        std::array<hlsl::float3, 4> cs{};
        for (std::size_t s = 0; s < n; ++s) {
            const std::array<std::uint8_t, 3>& c = r.colour[(i * n) + s];
            cs[s] = {static_cast<float>(c[0]) / 255.0F, static_cast<float>(c[1]) / 255.0F,
                     static_cast<float>(c[2]) / 255.0F};
        }
        shaders::FrameLayers stubbed{};
        stubbed.steps = {0U, 255U, 0U, 0U};
        const hlsl::float3 p = passes::composite_pixel(stubbed, std::span(cs.data(), n), {0.5F, 0.5F}, {1.0F, 1.0F});
        return std::array<std::uint32_t, 3>{static_cast<std::uint32_t>(p.x), static_cast<std::uint32_t>(p.y),
                                            static_cast<std::uint32_t>(p.z)};
    };
    Compared c;
    c.slivers = static_cast<std::size_t>(std::ranges::count_if(shapes, [](const Shape& sh) { return sh.sliver; }));
    c.ngons = static_cast<std::size_t>(std::ranges::count_if(shapes, [](const Shape& sh) { return sh.ngon; }));
    c.segments = segs.size();
    for (const StrokeSeg& g : segs) {
        const double len = pymath::hypot(static_cast<double>(g.bx - g.ax), static_cast<double>(g.by - g.ay));
        const auto margin = static_cast<double>(stroke_margin);
        c.quads += (len + (2.0 * margin)) * ((2.0 * static_cast<double>(g.hw)) + (2.0 * margin));
    }
    c.quads /= static_cast<double>(w) * static_cast<double>(h);
    if (const char* out = std::getenv("ATLAS_BUILDINGS_PPM"); out != nullptr) { // NOLINT: a test's diagnosis
        gpu::Pixels ref = got;
        for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
            const std::array<std::uint32_t, 3> want = resolved(exact_stroked, i);
            for (std::size_t k = 0; k < 3; ++k) ref.rgba[(i * 4) + k] = static_cast<std::uint8_t>(want[k]);
        }
        (void)gpu::write_ppm(ref, std::format("{}/exact {}.ppm", out, gpu::name_of(config)));
    }
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool covered = false;
        bool gradient = false; // a sample takes a window's glass's gradient, which the GPU evaluates in its own float
        std::uint32_t blends = 0; // the most strokes a sample of the pixel took, in any of the three rasters
        for (std::size_t s = 0; s < n; ++s) {
            const std::size_t k = (i * n) + s;
            covered = covered || exact[k] != uncovered || grown[k] != uncovered;
            gradient = gradient || (exact[k] != uncovered && shapes[static_cast<std::size_t>(exact[k])].glass &&
                                    shapes[static_cast<std::size_t>(exact[k])].paint.gradient);
            blends = std::max({blends, std::uint32_t{exact_stroked.blends[k]}, std::uint32_t{grown_stroked.blends[k]},
                               std::uint32_t{shrunk_stroked.blends[k]}});
        }
        if (!covered && blends == 0) continue;
        ++c.pixels;
        if (blends > 0) ++c.stroked;
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        // a pixel a stroke reaches within a level a blend (the attachment's 8 bits after each, lavapipe blending them
        // in fixed point; and one more where the composite averages samples), one a window's glass's gradient takes
        // within a level (the GPU's float, which may fuse its multiply-adds, against the model's), the rest exactly
        const std::uint32_t levels = blends + (blends > 0 && n > 1 ? 1U : 0U) + (gradient ? 1U : 0U);
        const auto same = [&](const std::array<std::uint32_t, 3>& v) {
            for (std::size_t k = 0; k < 3; ++k) {
                if ((v[k] > have[k] ? v[k] - have[k] : have[k] - v[k]) > levels) return false;
            }
            return true;
        };
        const std::array<std::uint32_t, 3> want = resolved(exact_stroked, i);
        if (same(want)) {
            ++c.agree;
            continue;
        }
        // a sample within the tolerance of a boundary ends with another shape (however alike their colours) when the
        // shapes grow or shrink by it, or takes another blend of a material face's when its shapes do
        bool ambiguous = false;
        bool sliver = false;
        bool tie = false;
        for (std::size_t s = 0; s < n; ++s) {
            const std::size_t k = (i * n) + s;
            ambiguous = ambiguous || exact[k] != grown[k] || exact[k] != shrunk[k] ||
                        exact_stroked.colour[k] != grown_stroked.colour[k] ||
                        exact_stroked.colour[k] != shrunk_stroked.colour[k];
            sliver = sliver || near_sliver[k] != uncovered;
            tie = tie || exact_raster.tie[k] != 0 || exact_stroked.tie[k] != 0;
        }
        if (ambiguous || same(resolved(grown_stroked, i)) || same(resolved(shrunk_stroked, i))) {
            ++c.boundary;
            continue;
        }
        if (tie) {
            ++c.tie;
            continue;
        }
        if (sliver) {
            ++c.sliver;
            continue;
        }
        if (config.target == gpu::CompositeTarget::resolve && on_seam(i % w, i / w, w, h)) {
            ++c.seam;
            continue;
        }
        if (c.wrong == 0) {
            c.first = std::format("pixel ({}, {}): {:02x}{:02x}{:02x} where the exact raster has {:02x}{:02x}{:02x}",
                                  i % w, i / w, have[0], have[1], have[2], want[0], want[1], want[2]);
        }
        if (c.wrong < 6 && std::getenv("ATLAS_BUILDINGS_DEBUG") != nullptr) { // NOLINT: a test's diagnosis
            std::string covering;
            const std::size_t column = i % w;
            const std::size_t row = i / w;
            for (std::size_t k = 0; k < shapes.size(); ++k) {
                double d = 1e300;
                for (std::size_t q = 0; q < n; ++q) {
                    const Pt p{.x = static_cast<double>(column) + samples[q].x,
                               .y = static_cast<double>(row) + samples[q].y};
                    d = std::min(d, signed_distance(p, shapes[k].poly));
                }
                if (d > 0.5 + shapes[k].stroke) continue;
                double inside = 0;
                const double z = depth_at(cam, shapes[k], (static_cast<double>(column) + samples[0].x) / density,
                                          (static_cast<double>(row) + samples[0].y) / density, &inside);
                covering +=
                    std::format("\n  shape {} colour {:06x} d {:.4f} stroke {:.4f} sliver {} depth {:.9g} "
                                "(inside its triangle by {:.4f}) slack {:.3g} points",
                                k, shapes[k].colour, d, shapes[k].stroke, shapes[k].sliver, z, inside, shapes[k].slack);
                for (const Pt& q : shapes[k].poly) covering += std::format(" ({:.3f}, {:.3f})", q.x, q.y);
            }
            for (std::size_t q = 0; q < n; ++q) {
                covering += std::format("\n  sample {}: exact {} grown {} shrunk {}", q, exact[(i * n) + q],
                                        grown[(i * n) + q], shrunk[(i * n) + q]);
            }
            MESSAGE(std::format("pixel ({}, {}): gpu {:02x}{:02x}{:02x} exact {:02x}{:02x}{:02x}{}", i % w, i / w,
                                have[0], have[1], have[2], want[0], want[1], want[2], covering));
        }
        ++c.wrong;
    }
    return c;
}

/// A pack the scene draws from: its store id and its file.
struct PackFile {
    std::string id;
    std::string path;
};

/// The bundled render packs in `dir` that are there.
[[nodiscard]] std::vector<PackFile> bundled_packs(const std::string& dir) {
    std::vector<PackFile> out;
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        if (std::filesystem::exists(path)) out.push_back({.id = name, .path = path.string()});
    }
    return out;
}

/// Whether make_scene's world holds its tiles' walls in the heaps' second buffer: heaps of two 64 MB buffers, the first
/// filled before any tile is built (ENGINE.md, one pack per country, step 7).
bool second_buffer = false; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): the test's own switch

/// Whether make_scene's buildings pass builds every tile as a level's tile is: each wall its one cell, no windowed
/// walls, its records lean on its tile's grid (ENGINE.md, one pack per country, step 7).
bool lean_tiles = false; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): the test's own switch

/// Whether make_scene's frames are the app's map's (frame::FrameOptions::grounded; their cameras out of engine mode).
bool app_map = false; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): the test's own switch

/// Makes the scene for `config` on a w × h output, drawing from `packs`.
[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              std::span<const PackFile> packs, std::uint32_t clear) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = gpu::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite, and the buildings pass of its own (the test reads its twin)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Buildings>();
    owned->lean_every_tile(lean_tiles);
    s.buildings = owned.get();
    passes.push_back(std::move(owned));
    // and the asked building's house's, after it as in the registry (it draws nothing until a frame has a house)
    auto house = std::make_unique<passes::House>();
    s.house = house.get();
    passes.push_back(std::move(house));
    const std::array<float, 4> c{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                 static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                 static_cast<float>(clear & 0xFFU) / 255.0F, 1.0F};
    auto builder = frame::FrameBuilder::create(device, cache, std::move(passes),
                                               {.overdraw = false, .clear = c, .steps = 0, .grounded = app_map});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, builder.has_value(), builder.error().text());
    s.builder = std::move(*builder);
    scene::WorldOptions options{.workers = 3, .clock = {}};
    constexpr vk::DeviceSize part = vk::DeviceSize{64} << 20U;
    if (second_buffer) options.heaps = {.total = 2 * part, .binding = part};
    auto world = scene::World::create(device, s.builder->passes(), options);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, world.has_value(), world.error().text());
    s.world = std::move(*world);
    if (second_buffer) {
        auto filled = s.world->heaps()->allocate(scene::HeapKind::walls, static_cast<std::uint32_t>(part), 16);
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, filled.has_value(), filled.error().text());
    }
    for (const PackFile& pack : packs) {
        auto added = s.world->store().add({.id = pack.id, .kind = tiles::PackKind::bundled, .path = pack.path});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    }
    auto renderer = gpu::Renderer::create(device, *s.output, *s.builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    s.renderer = std::move(*renderer);
    return s;
}

/// The windows' decide stages a frame runs (passes::Buildings::WindowStage's order).
using Stages = std::array<bool, passes::Buildings::window_stages>;

/// The least shop glazings a frame draws lit at night (one of their building's places open) and dark.
struct Glazings {
    std::size_t open = 0;
    std::size_t closed = 0;
};

/// Draws `cam` in X1 and C, at rest and moving, at moment `th` over `packs`, and holds every pixel to the exact raster
/// of the pass's twin; the twin emits at least `least` elements. With `then`, the first frame's moment changes to it
/// (the CPU's light lights every drawn tile again) and that frame is held to the twin too. With `painter`, under the
/// painter switch: the renders' order, held to the twin's painter order. The frame lays at least `least_kit` records of
/// a kit's walls' lines and eaves' shadows. Each windows' decide stage runs where the frame has its part, over the
/// walls that have it; with `stages`, the frame runs those alone. With `glazings`, the twin draws at least so many
/// shop glazings lit and dark at night (the GPU's, pixel for pixel).
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least, const style::ThemeBlock* then = nullptr,
                  bool painter = false, std::uint32_t least_kit = 0, const Stages* stages = nullptr,
                  const Glazings* glazings = nullptr) {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::uint32_t clear = 0x808080;
    for (const gpu::Configuration config : {gpu::Configuration{.config = gpu::FrameConfig::x1,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::direct},
                                            gpu::Configuration{.config = gpu::FrameConfig::c,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::resolve}}) {
        if (!device.facts().supports(config.config)) continue;
        for (const bool rest : {true, false}) {
            style::CameraState c = cam;
            c.flags = rest ? (c.flags | style::camera_flag::rest) : (c.flags & ~style::camera_flag::rest);
            auto made = make_scene(*session, *cache, config, c.width_px, c.height_px, packs, clear);
            ATLAS_REQUIRE(made.has_value());
            Scene& s = *made;
            s.builder->set_painter(painter);
            const auto pixels = draw(s, c, th);
            ATLAS_REQUIRE(pixels.has_value());
            CHECK_MESSAGE(s.settled, view << ": the world did not settle");
            if (!s.lit) MESSAGE(view << ": drawn without every tile's sun bits (the sun's worker gave none)");
            if (lean_tiles) CHECK(s.buildings->lean_draws() > 0);
            const scene::HeapStats heap = s.world->heaps()->stats();
            const auto walls = static_cast<std::size_t>(scene::HeapKind::walls);
            if (second_buffer) {
                // every tile's walls lie in the second buffer
                std::size_t high = 0;
                for (const std::shared_ptr<const scene::ResidentTile>& t : s.world->residency().resident()) {
                    for (const scene::PassTile& p : t->passes) {
                        for (const scene::HeapRange& r : p.ranges) {
                            if (r && r.kind == scene::HeapKind::walls) {
                                CHECK(r.offset >= scene::heap_high_base);
                                ++high;
                            }
                        }
                    }
                }
                CHECK(high > 0);
            }
            if (std::getenv("ATLAS_BUILDINGS_DEBUG") != nullptr) { // NOLINT: a test's diagnosis
                for (const scene::CoverTile& t : s.world->cover()->tiles()) {
                    MESSAGE(std::format("cover {}/{}/{} drawn {} cells {:016x} fade {} offset {} {} {} scale {}",
                                        t.key.z, t.key.x, t.key.y, t.drawn != nullptr ? t.drawn->handle : 0U, t.cells,
                                        t.fade, t.place.offset.x, t.place.offset.y, t.place.offset.z, t.place.scale.x));
                }
            }
            twin::Stream stream;
            s.buildings->emit(s.builder->state(), stream, passes::Buildings::draw_phase);
            const twin::Decoded decoded = twin::decode(stream.bytes());
            ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
            const Compared r = compare(*pixels, decoded.document, s.buildings->depths(), distances(s),
                                       s.builder->state().block, config, c.density, clear);
            const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config), rest ? "at rest" : "moving",
                                                 painter ? " (painter's order)" : "");
            const passes::Buildings::StrokeCounts strokes = s.buildings->stroke_counts();
            MESSAGE(name << ": " << stream.elements() << " elements, " << r.segments << " stroke segments from "
                         << strokes.records << " records in " << strokes.jobs << " jobs, their quads " << r.quads
                         << " frames; " << r.pixels << " pixels of buildings (" << r.stroked
                         << " reached by a stroke), " << r.agree << " the exact raster's, " << r.boundary
                         << " within 0.02 px of a shape's boundary, " << r.tie << " where two are tied in depth, "
                         << r.sliver << " near the " << r.slivers << " quads snapping made concave or crossed or the "
                         << r.ngons << " n-gons' boundaries, " << r.seam << " on the composite's seam, " << r.wrong
                         << " otherwise; " << s.world->tiles_line() << "; the walls heap "
                         << static_cast<double>(heap.used[walls]) / (1 << 20) << " MB of the heaps' "
                         << static_cast<double>(heap.total_used()) / (1 << 20) << " MB; "
                         << frame::text_of(s.builder->total()));
            // what the GPU's cull kept of the walls', near cells' and roofs' triangles (passes/Kept.cpp), the frame
            // done
            const passes::Buildings::KeptCounts kept = s.buildings->kept_counts();
            MESSAGE(name << ": kept walls " << kept.kept[0] << " of " << kept.submitted[0] << ", near cells "
                         << kept.kept[1] << " of " << kept.submitted[1] << ", roofs " << kept.kept[2] << " of "
                         << kept.submitted[2] << " triangles; drawn from shape records " << kept.shaped[0] << ", "
                         << kept.shaped[1] << " and " << kept.shaped[2] << ", " << kept.shape_bytes
                         << " bytes of records (their buffers " << kept.shape_memory << ")");
            const passes::Buildings::WindowCounts windows = s.buildings->window_counts();
            MESSAGE(name << ": " << windows.walls << " facade walls, " << windows.windows << " windows, "
                         << windows.nears << " near ones, " << windows.facades << " facades, " << windows.churches
                         << " church windows, " << windows.material_fills << " material fills and "
                         << windows.material_lines << " material lines; " << windows.beams << " hoist beams, "
                         << windows.roof_lines << " roof lines; a kit's walls' " << windows.kit_lines << " lines and "
                         << windows.kit_shadows << " eaves' shadows");
            CHECK(windows.kit_lines + windows.kit_shadows >= least_kit);
            // the shop floors' glazing the twin draws (its shop windows' glass): at night lit while one of its
            // building's places is open, else dark
            const passes::ShopGlass paints = passes::shop_glass_of(s.builder->state());
            const auto is = [](hlsl::float3 a, hlsl::float3 b) { return a.x == b.x && a.y == b.y && a.z == b.z; };
            std::size_t open = 0;
            std::size_t closed = 0;
            std::size_t glazed = 0;
            for (const twin::ElementDepth& e : s.buildings->depths()) {
                for (const twin::WindowsDepth& wd : e.windows) {
                    if (!wd.shop) continue;
                    ++glazed;
                    open += is(wd.glass.top, shaders::building_rgb(paints.open)) ? 1 : 0;
                    closed += is(wd.glass.top, shaders::building_rgb(paints.closed)) ? 1 : 0;
                }
            }
            MESSAGE(name << ": " << glazed << " shop glazings, " << open << " lit as open and " << closed
                         << " dark as closed");
            if (glazings != nullptr) {
                CHECK(open >= glazings->open);
                CHECK(closed >= glazings->closed);
                CHECK(open + closed == glazed);
            }
            // each decide stage dispatched where it has walls (its lanes), and those its part's alone: the windows'
            // where the frame decides windows, the churches' where church windows, the material faces' where their
            // records; none past the frame's facade walls
            using Stage = passes::Buildings::WindowStage;
            const auto walls_of = [&](Stage stage) { return windows.stage_walls.at(static_cast<std::size_t>(stage)); };
            const auto dispatched = [&](std::string_view kind) {
                std::uint32_t n = 0;
                for (const frame::PassCounters& p : s.builder->counters()) {
                    for (const frame::KindCount& k : p.kinds()) {
                        if (k.kind == kind) n += k.count.dispatches;
                    }
                }
                return n;
            };
            MESSAGE(name << ": the decide stages' walls: windows " << walls_of(Stage::windows) << ", facades "
                         << walls_of(Stage::facades) << ", church windows " << walls_of(Stage::churches)
                         << ", material faces " << walls_of(Stage::materials));
            CHECK(dispatched("windows") == (walls_of(Stage::windows) > 0 ? 1U : 0U));
            CHECK(dispatched("window facades") == (walls_of(Stage::facades) > 0 ? 1U : 0U));
            CHECK(dispatched("window churches") == (walls_of(Stage::churches) > 0 ? 1U : 0U));
            CHECK(dispatched("window materials") == (walls_of(Stage::materials) > 0 ? 1U : 0U));
            if (windows.windows == 0) CHECK(walls_of(Stage::windows) == 0);
            if (windows.facades > 0) CHECK(walls_of(Stage::facades) > 0);
            CHECK((walls_of(Stage::churches) > 0) == (windows.churches > 0));
            CHECK((walls_of(Stage::materials) > 0) == (windows.material_fills + windows.material_lines > 0));
            for (const std::uint32_t n : windows.stage_walls) CHECK(n <= windows.walls);
            if (stages != nullptr) {
                for (std::size_t i = 0; i < passes::Buildings::window_stages; ++i) {
                    CAPTURE(i);
                    CHECK((windows.stage_walls.at(i) > 0) == stages->at(i));
                }
            }
            CHECK(kept.kept[0] <= kept.submitted[0]);
            CHECK(kept.kept[1] <= kept.submitted[1]);
            CHECK(kept.kept[2] <= kept.submitted[2]);
            CHECK(stream.elements() >= least);
            // the roofs' thin edges drawn: a record a plain primitive of the drawn runs
            CHECK(s.buildings->strokes());
            CHECK(strokes.records >= r.segments / 5);
            // the sunny moment's sun bits reach the frame: some faces lit by them, as the GPU draws them
            const passes::Buildings::Emitted emitted = s.buildings->emitted();
            MESSAGE(name << ": " << emitted.sunlit << " of " << emitted.elements << " elements lit by their sun bit");
            CHECK(emitted.elements == stream.elements());
            if (s.lit) CHECK(emitted.sunlit > emitted.elements / 20);
            CHECK_MESSAGE(r.wrong == 0, name << ": " << r.first);
            CHECK(static_cast<double>(r.boundary) <= 0.01 * static_cast<double>(r.pixels));
            CHECK(static_cast<double>(r.tie) <= 0.01 * static_cast<double>(r.pixels));
            CHECK(r.sliver <= 4 * (r.slivers + r.ngons) + 4); // a few pixels each at most
            // the same pixels however the kept walls and near cells are decided (passes/Kept.cpp): each at each point,
            // each once into its shape record, and the two in one list (its first chunk from records), the frame's
            // inputs as they were; the roofs at each point
            constexpr std::uint32_t every = std::numeric_limits<std::uint32_t>::max();
            for (const std::uint32_t most : {0U, 1U, every}) {
                s.buildings->most_shape_chunks(most);
                const auto still = draw_still(s, c, th);
                ATLAS_REQUIRE(still.has_value());
                const passes::Buildings::KeptCounts k = s.buildings->kept_counts();
                std::size_t differ = 0;
                for (std::size_t i = 0; i + 4 <= still->rgba.size() && i + 4 <= pixels->rgba.size(); i += 4) {
                    differ += std::equal(still->rgba.begin() + static_cast<std::ptrdiff_t>(i),
                                         still->rgba.begin() + static_cast<std::ptrdiff_t>(i + 4),
                                         pixels->rgba.begin() + static_cast<std::ptrdiff_t>(i))
                                  ? 0
                                  : 1;
                }
                MESSAGE(name << ", at most " << (most == every ? std::string("every") : std::to_string(most))
                             << " chunk of records a list: drawn from them " << k.shaped[0] << ", " << k.shaped[1]
                             << " and " << k.shaped[2] << " triangles; " << differ << " pixels differ");
                CHECK_MESSAGE(*still == *pixels, name << ", at most " << most << " chunks of records a list");
                CHECK(k.kept == kept.kept);
                for (std::size_t l = 0; l < 2; ++l) {
                    CAPTURE(l);
                    const std::uint64_t chunk = std::uint64_t{passes::Buildings::edged_chunk} * 14;
                    if (most == 0) CHECK(k.shaped.at(l) == 0);
                    if (most == 1) CHECK(k.shaped.at(l) <= std::min(chunk, k.kept.at(l)));
                    if (most == every) CHECK(k.shaped.at(l) == k.kept.at(l));
                }
                CHECK(k.shaped[2] == 0);
            }
            // and while a tile fades (ENGINE.md's standing fades): every kept job's tile on the dither's levels from a
            // quarter to three quarters, the records' pipeline while fading (walls_shape_fading_vs) taking its tile's
            // fade through the place its record stands for as each decided at each point takes its job's
            s.buildings->fade_every(std::array<float, 2>{0.25F, 0.75F});
            std::optional<gpu::Pixels> fading;
            for (const std::uint32_t most : {0U, every}) {
                s.buildings->most_shape_chunks(most);
                const auto still = draw_still(s, c, th);
                ATLAS_REQUIRE(still.has_value());
                const passes::Buildings::KeptCounts k = s.buildings->kept_counts();
                CHECK(k.kept == kept.kept);
                if (!fading) {
                    // each at each point: the walls dithered
                    if (kept.kept[0] + kept.kept[1] > 0) CHECK(*still != *pixels);
                    fading = *still;
                    continue;
                }
                if (most == every) CHECK(k.shaped[0] + k.shaped[1] == k.kept[0] + k.kept[1]);
                CHECK_MESSAGE(*still == *fading, name << ", fading, every chunk from records");
            }
            s.buildings->fade_every(std::nullopt);
            s.buildings->most_shape_chunks(std::nullopt);
            for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
            if (const std::string out = environment("ATLAS_BUILDINGS_PPM"); !out.empty()) {
                CHECK(gpu::write_ppm(*pixels, std::format("{}/{}.ppm", out, name)));
            }
            if (then != nullptr && rest && config.config == gpu::FrameConfig::x1) {
                // another moment: every drawn tile's colours again, lit ahead on the pass's own thread and copied in
                // by the frame that takes it, both frame slots reading them
                s.buildings->reset_lighting();
                const auto again = redraw(s, c, *then);
                ATLAS_REQUIRE(again.has_value());
                const passes::Buildings::Lighting l = s.buildings->lighting();
                twin::Stream after;
                s.buildings->emit(s.builder->state(), after, passes::Buildings::draw_phase);
                const twin::Decoded d2 = twin::decode(after.bytes());
                ATLAS_REQUIRE(d2.error.empty(), d2.error);
                const Compared r2 = compare(*again, d2.document, s.buildings->depths(), distances(s),
                                            s.builder->state().block, config, c.density, clear);
                MESSAGE(name << ", the moment changed: lit ahead " << l.ahead_tiles << " tiles, " << l.ahead_faces
                             << " faces, in " << l.ahead_ms << " ms; the render thread copied " << l.copied
                             << " tiles in " << l.copy_ms << " ms and lit " << l.tiles << " tiles, " << l.faces
                             << " faces, in " << l.ms << " ms (the longest " << l.longest_ms << " ms); " << r2.pixels
                             << " pixels, " << r2.wrong << " wrong");
                CHECK(l.copied > 0);
                CHECK(l.tiles == 0);
                CHECK_MESSAGE(r2.wrong == 0, name << ", the moment changed: " << r2.first);
            }
            s.renderer.reset();
        }
    }
}

/// Writes a synthetic tile of tests/building/fixture.hpp (by default its every roof shape, `features` of them) as a
/// render pack of its own, named for `name`; its path.
[[nodiscard]] std::string synthetic_pack(test::TileBytes t = test::building::tile(),
                                         std::uint32_t features = test::building::tile_features,
                                         std::string_view name = "shapes") {
    return test::building::write_pack(std::move(t), features, std::format("buildings-test-{}", name));
}

/// The camera state that snaps onto the grachten pack's view `name` (its knot's target, yaw, pitch and distance in the
/// map's terms), on a w × h frame at density d; nothing without the pack.
[[nodiscard]] std::optional<style::CameraState> region_view(const std::string& dir, std::string_view name,
                                                            std::uint32_t w, std::uint32_t h, double d) {
    auto source = tiles::MappedSource::open(dir + "/grachten.atlas");
    if (!source) return std::nullopt;
    auto pack = tiles::Pack::open(std::move(*source));
    if (!pack || pack->head() == nullptr) return std::nullopt;
    const auto ladder = view::Ladder::of(*pack->head());
    if (!ladder) return std::nullopt;
    for (const view::Ladder::Knot& k : ladder->knots()) {
        if (k.name != name) continue;
        const view::GroundFrame& f = ladder->frame();
        const double lat = f.lat0 - (k.target[2] / f.kz);
        const double lon = f.lon0 + (k.target[0] / f.kx);
        style::CameraState c = camera(lat, lon, view::bearing_of(k.yaw), 90.0 - k.pitch, k.dist, w, h, d);
        c.principal_y = std::isnan(k.cy) ? c.height_dp / 2.0 : k.cy; // EngineVersion.knotView's
        return c;
    }
    return std::nullopt;
}

/// How many pixels' colours differ between `a` and `b` (all of them when their sizes do).
[[nodiscard]] std::size_t differing(const gpu::Pixels& a, const gpu::Pixels& b) {
    if (a.rgba.size() != b.rgba.size()) return std::max(a.rgba.size(), b.rgba.size()) / 4;
    std::size_t out = 0;
    for (std::size_t i = 0; i + 3 < a.rgba.size(); i += 4) {
        if (a.rgba[i] != b.rgba[i] || a.rgba[i + 1] != b.rgba[i + 1] || a.rgba[i + 2] != b.rgba[i + 2]) ++out;
    }
    return out;
}

/// The calling thread's CPU time so far (ms): what it worked, whatever else the machine runs.
[[nodiscard]] double thread_ms() {
    timespec t{};
    (void)clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return (static_cast<double>(t.tv_sec) * 1e3) + (static_cast<double>(t.tv_nsec) / 1e6);
}

/// Draws `cam` at rest in X1 over `packs` at moment `from` until it settles, then at `to`, then at `from` again, frame
/// by frame. Every frame shows one moment whole, pixel for pixel the frame a scene drawn from scratch at it draws (the
/// settled frame at `from`; a scene of its own at `to`): the old one until the pass's light ahead is done (the first
/// frame after a change at least), and from the frame that takes the new one on, in both frame slots, the new one.
/// That frame copies in every drawn tile's colours, lit ahead; no frame lights a tile on the render thread, and no
/// other copies one. A new moment given up a frame after it is asked for leaves the frames at the moment shown, with
/// nothing copied or lit. Says each frame's time on the render thread (its CPU time, and from its start to its present
/// returning), its stage F and recording, and what the light did.
void check_moments(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                   const style::ThemeBlock& from, const style::ThemeBlock& to) {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const gpu::Configuration x1{.config = gpu::FrameConfig::x1,
                                .route = gpu::Route::renderpass,
                                .form = gpu::FrameForm::composite,
                                .target = gpu::CompositeTarget::direct};
    style::CameraState c = cam;
    c.flags |= style::camera_flag::rest;
    // the new moment drawn from scratch, by a scene of its own
    std::optional<gpu::Pixels> fresh;
    {
        auto made = make_scene(*session, *cache, x1, c.width_px, c.height_px, packs, 0x808080);
        ATLAS_REQUIRE(made.has_value());
        fresh = draw(*made, c, to);
        ATLAS_REQUIRE(fresh.has_value());
        made->renderer.reset();
    }
    auto made = make_scene(*session, *cache, x1, c.width_px, c.height_px, packs, 0x808080);
    ATLAS_REQUIRE(made.has_value());
    Scene& s = *made;
    const std::optional<gpu::Pixels> settled = draw(s, c, from);
    ATLAS_REQUIRE(settled.has_value());
    CHECK_MESSAGE(s.settled, view << ": the world did not settle");
    struct Change {
        const style::ThemeBlock* th;
        const gpu::Pixels* want;
        const gpu::Pixels* before;
        const char* name;
    };
    for (const Change& change : {Change{.th = &to, .want = &*fresh, .before = &*settled, .name = "the new moment"},
                                 Change{.th = &from, .want = &*settled, .before = &*fresh, .name = "back again"}}) {
        int taken = -1;
        for (int n = 0; n < 40 && (taken < 0 || n <= taken + 2); ++n) {
            s.now += 0.25;
            s.builder->set_inputs({.camera = c,
                                   .theme = *change.th,
                                   .seconds = s.now,
                                   .mode = frame::Mode::structural,
                                   .scene = s.world.get()});
            s.buildings->reset_lighting();
            const double cpu = thread_ms();
            auto report = s.renderer->draw(0);
            const double worked = thread_ms() - cpu;
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            s.renderer->drain();
            auto pixels = s.output->read(s.output->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            const bool now = shows(s, *change.th);
            if (now && taken < 0) taken = n;
            const passes::Buildings::Lighting l = s.buildings->lighting();
            const std::size_t wrong = differing(*pixels, now ? *change.want : *change.before);
            MESSAGE(std::format("{}, {}: frame {} (slot {}) shows the {} moment: the render thread worked {:.2f} ms "
                                "({:.2f} ms to its present), stage F {:.2f} ms, recording {:.2f} ms; lit {} tiles, {} "
                                "faces, in {:.2f} ms, copied {} in {:.2f} ms; lit ahead {} tiles, {} faces, in {:.2f} "
                                "ms; {} pixels unlike that moment's frame drawn from scratch",
                                view, change.name, n, s.builder->state().slot, now ? "new" : "old", worked,
                                report->cpu_ms, s.builder->stage_f_ms(), s.builder->record_ms(), l.tiles, l.faces, l.ms,
                                l.copied, l.copy_ms, l.ahead_tiles, l.ahead_faces, l.ahead_ms, wrong));
            CHECK_MESSAGE(wrong == 0, view << ", " << change.name << ": frame " << n << " shows the "
                                           << (now ? "new" : "old") << " moment with " << wrong << " pixels unlike it");
            CHECK(l.tiles == 0);
            if (n == taken) {
                CHECK(l.copied > 0);
                CHECK(l.ahead_tiles == l.copied);
            } else {
                CHECK(l.copied == 0);
            }
            if (taken >= 0) CHECK(now); // once shown, the new moment stays
            // the light ahead given its time once two frames have gone without waiting for it
            if (!now && n >= 1) s.buildings->wait_ahead();
        }
        CHECK_MESSAGE(taken >= 1, view << ", " << change.name << ": the new moment shown at frame " << taken
                                       << " (the first after the change shows the old one, its light under way)");
    }
    // a new moment asked for and given up a frame later (the inputs back at the moment shown): the frames show the
    // moment shown, whole, and its light ahead is let go of, nothing copied in or lit
    for (int n = 0; n < 3; ++n) {
        s.now += 0.25;
        s.builder->set_inputs({.camera = c,
                               .theme = n == 0 ? to : from,
                               .seconds = s.now,
                               .mode = frame::Mode::structural,
                               .scene = s.world.get()});
        s.buildings->reset_lighting();
        auto report = s.renderer->draw(0);
        ATLAS_REQUIRE(report.has_value(), report.error().text());
        s.renderer->drain();
        auto pixels = s.output->read(s.output->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        const passes::Buildings::Lighting l = s.buildings->lighting();
        const std::size_t wrong = differing(*pixels, *settled);
        CHECK(shows(s, from));
        CHECK_MESSAGE(wrong == 0, view << ", the moment given up: frame " << n << " has " << wrong
                                       << " pixels unlike the moment shown");
        CHECK(l.tiles + l.copied + l.ahead_tiles == 0);
    }
    s.buildings->wait_ahead(); // nothing asked for: at once
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, view << ": " << m);
    s.renderer.reset();
}

} // namespace

TEST_CASE("buildings: every roof shape and a houseboat drawn on the host's Vulkan, pixel for pixel the exact shapes of "
          "their twin") {
    // the synthetic tile of tests/building/fixture.hpp in a render pack of its own (so CI draws it without the
    // bundled packs): near (each wall's cells) and far (its one cell), from the side and straight down (every flat top
    // from above), the houseboat as a boat on a moment with weather and as a building on one without
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    const double lat = 52.37422;
    const double lon = 4.88486;
    style::ThemeBlock dry_th = sunny();
    dry_th.flags &= ~style::theme_flag::weather;
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "synthetic near", packs, sunny(), 15);
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "synthetic near", packs, sunny(), 15, nullptr,
                 true);
    check_frames(camera(lat, lon, 200.0, 60.0, 260.0, 1080, 2400, d), "synthetic near dry", packs, dry_th, 15);
    // its walls beyond the windows' reach, their flat detail within the facades' 760 m: the facades' stage alone, as
    // the phone's frames at about z 17
    const Stages facades_alone{false, true, false, false};
    check_frames(camera(lat, lon, 300.0, 45.0, 650.0, 1080, 2400, d), "synthetic far", packs, sunny(), 30, nullptr,
                 false, 0, &facades_alone);
    check_frames(camera(lat, lon, 0.0, 0.0, 220.0, 1080, 2400, d), "synthetic straight down", packs, sunny(), 20);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE(
    "buildings: a tile whose walls lie in the heaps' second buffer drawn on the host's Vulkan, pixel for pixel the "
    "exact shapes of its twin") {
    // the second buffer's ranges from heap_high_base (ENGINE.md, one pack per country, step 7): the walls' heap read
    // there by every stage, the windows' and strokes' decide stages among them
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    second_buffer = true;
    check_frames(camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, d), "synthetic near, second buffer", packs,
                 sunny(), 15);
    check_frames(camera(52.37422, 4.88486, 300.0, 45.0, 650.0, 1080, 2400, d), "synthetic far, second buffer", packs,
                 sunny(), 30);
    second_buffer = false;
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: the walls' material faces drawn on the host's Vulkan over what lies under them, pixel for pixel "
          "their twin's") {
    // fixture.hpp's materials tile in a render pack of its own: a glass office's glass over its shop glazing, then its
    // floors and mullions; a metal shed's ribs; a stone home's balconies; a parking garage's decks; a canal house's
    // hoist beam; near with weather by day, under the painter switch, and at night (no ribs then) from the other side
    const std::string path =
        synthetic_pack(test::building::materials_tile(), test::building::materials_features, "materials");
    const std::array<PackFile, 1> packs{PackFile{.id = "materials", .path = path}};
    const double d = 1080.0 / 412.0;
    const double lat = 52.37422;
    const double lon = 4.88486;
    style::ThemeBlock night_th = sunny();
    night_th.flags |= style::theme_flag::night;
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "materials near", packs, sunny(), 8);
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "materials near", packs, sunny(), 8, nullptr,
                 true);
    check_frames(camera(lat, lon, 200.0, 50.0, 260.0, 1080, 2400, d), "materials at night", packs, night_th, 8);
    // the garage's decks, the stone home's balconies, the office and the shed's ribs, from the south; the canal house's
    // hoist beam under its near gable, at true depth and at its slot among the slopes
    check_frames(camera(52.37400, 4.88380, 0.0, 60.0, 260.0, 1080, 2400, d), "materials west", packs, sunny(), 8);
    check_frames(camera(52.37400, 4.88380, 0.0, 60.0, 260.0, 1080, 2400, d), "materials west", packs, sunny(), 8,
                 nullptr, true);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE(
    "buildings: a tradition's kit drawn on the host's Vulkan, its roofs' eaves over the walls, its gables facing out, "
    "its hoist beams, tile courses and lit ridges, its walls' stucco ground floor, timber boards and corner boards, "
    "the shadows of its eaves, pixel for pixel their twin's") {
    // fixture.hpp's kit tile in a pack whose style carries its tradition (the kit's eaves, band and boards), in the
    // view the cameras snap to (the pack's home, as the material faces' case): near with weather by day, where the view
    // resolves the courses of the tiled roof and of the slated one, every kit wall's eaves' shadow; under the painter
    // switch; closer, where it resolves the boards' lines too; at night (neither courses nor ridges, nor boards' lines
    // nor shadows); and without weather, every roof the plain one and no wall a kit's detail
    const std::string path = test::building::write_pack(test::building::kit_tile(), test::building::kit_features,
                                                        "buildings-test-kit", test::building::kit_pack());
    const std::array<PackFile, 1> packs{PackFile{.id = "kit", .path = path}};
    const double d = 1080.0 / 412.0;
    const double lat = 52.37422;
    const double lon = 4.88486;
    style::ThemeBlock night_th = sunny();
    night_th.flags |= style::theme_flag::night;
    style::ThemeBlock dry_th = sunny();
    dry_th.flags &= ~style::theme_flag::weather;
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "kit near", packs, sunny(), 3, nullptr, false, 6);
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "kit near", packs, sunny(), 3, nullptr, true, 6);
    // closer, where the view resolves the wooden house's boards (focal / d · 0.2 > 2.2): their lines between its corner
    // boards, under its eaves' shadows; under the painter switch too
    check_frames(camera(lat, lon, 20.0, 50.0, 160.0, 1080, 2400, d), "kit close", packs, sunny(), 3, nullptr, false,
                 100);
    check_frames(camera(lat, lon, 20.0, 50.0, 160.0, 1080, 2400, d), "kit close", packs, sunny(), 3, nullptr, true,
                 100);
    check_frames(camera(lat, lon, 200.0, 50.0, 260.0, 1080, 2400, d), "kit at night", packs, night_th, 3);
    check_frames(camera(lat, lon, 20.0, 50.0, 260.0, 1080, 2400, d), "kit dry", packs, dry_th, 3);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

namespace {

/// One more frame of `cam` at `th` in the settled scene `s`, with the parts `off` left out (style::camera_part): the
/// buildings' window counts.
[[nodiscard]] passes::Buildings::WindowCounts parts_frame(Scene& s, style::CameraState cam, const style::ThemeBlock& th,
                                                          std::uint64_t off) {
    cam.parts = off;
    s.now += 0.25;
    s.builder->set_inputs(
        {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
    auto report = s.renderer->draw(0);
    if (!report) FAIL("a frame failed: " << report.error().text());
    s.renderer->drain();
    return s.buildings->window_counts();
}

} // namespace

TEST_CASE(
    "buildings: a facade's parts the map leaves out, which the bundled packs do not show: the material faces, the "
    "hoist beams and a kit's details, each one's records gone with its switch and the others' as they were") {
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const gpu::Configuration x1{.config = gpu::FrameConfig::x1,
                                .route = gpu::Route::renderpass,
                                .form = gpu::FrameForm::composite,
                                .target = gpu::CompositeTarget::direct};
    namespace part = style::camera_part;
    using Counts = passes::Buildings::WindowCounts;
    const double d = 1080.0 / 412.0;
    // the materials tile near (its glass office, metal shed, stone home and garage, the canal house's hoist beam) and
    // the kit tile close (its courses, ridges, boards' lines and eaves' shadows), as their own cases draw them
    const std::string materials =
        synthetic_pack(test::building::materials_tile(), test::building::materials_features, "materials-parts");
    const std::string kit = test::building::write_pack(test::building::kit_tile(), test::building::kit_features,
                                                       "buildings-test-kit-parts", test::building::kit_pack());
    struct View {
        const char* name;
        std::string path;
        style::CameraState camera;
    };
    for (const View& v :
         {View{.name = "materials",
               .path = materials,
               .camera = camera(52.37400, 4.88380, 0.0, 60.0, 260.0, 1080, 2400, d)},
          View{.name = "kit", .path = kit, .camera = camera(52.37422, 4.88486, 20.0, 50.0, 160.0, 1080, 2400, d)}}) {
        const std::string_view view = v.name;
        CAPTURE(view);
        const std::array<PackFile, 1> packs{PackFile{.id = v.name, .path = v.path}};
        auto made = make_scene(*session, *cache, x1, v.camera.width_px, v.camera.height_px, packs, 0x808080);
        ATLAS_REQUIRE(made.has_value());
        Scene& s = *made;
        style::CameraState cam = v.camera;
        cam.flags |= style::camera_flag::rest;
        ATLAS_REQUIRE(draw(s, cam, sunny()).has_value());
        const Counts all = parts_frame(s, cam, sunny(), 0);
        const auto materials_of = [](const Counts& c) { return c.material_fills + c.material_lines; };
        const auto kit_of = [](const Counts& c) { return c.kit_lines + c.kit_shadows + c.roof_lines; };
        // each part: its records gone with its switch, the windows' and the others' as they were
        const Counts no_materials = parts_frame(s, cam, sunny(), part::material_faces);
        CHECK(materials_of(no_materials) == 0U);
        CHECK(no_materials.stage_walls.at(std::to_underlying(passes::Buildings::WindowStage::materials)) == 0U);
        CHECK(no_materials.beams == all.beams);
        CHECK(kit_of(no_materials) == kit_of(all));
        CHECK(no_materials.windows == all.windows);
        const Counts no_beams = parts_frame(s, cam, sunny(), part::hoist_beams);
        CHECK(no_beams.beams == 0U);
        CHECK(materials_of(no_beams) == materials_of(all));
        CHECK(kit_of(no_beams) == kit_of(all));
        const Counts no_kit = parts_frame(s, cam, sunny(), part::kit_details);
        CHECK(kit_of(no_kit) == 0U);
        CHECK(materials_of(no_kit) == materials_of(all));
        CHECK(no_kit.windows == all.windows);
        // the facades' detail as a group: no facade wall decided, no record of any part
        const Counts none = parts_frame(s, cam, sunny(), part::facade_detail);
        CHECK(none.walls == 0U);
        CHECK(materials_of(none) + kit_of(none) + none.beams + none.windows + none.churches + none.facades == 0U);
        // and every one of them again
        const Counts again = parts_frame(s, cam, sunny(), 0);
        CHECK(materials_of(again) == materials_of(all));
        CHECK(kit_of(again) == kit_of(all));
        CHECK(again.beams == all.beams);
        MESSAGE(
            std::format("{}: {} material records, {} hoist beams, {} kit lines and shadows; none with their switches",
                        v.name, materials_of(all), all.beams, kit_of(all)));
        if (view == "materials") {
            CHECK(materials_of(all) > 0U);
            CHECK(all.beams > 0U);
        } else {
            CHECK(kit_of(all) > 0U);
        }
    }
    std::error_code ignored;
    std::filesystem::remove(materials, ignored);
    std::filesystem::remove(kit, ignored);
}

TEST_CASE("buildings: left out and drawn again, their pass let go of by every tile and built anew onto each: the "
          "picture at rest after the same, pixel for pixel (ENGINE.md's parts)") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Westerkerk home is not drawn here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::x1,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::direct};
    style::CameraState cam = home(1080, 2400, 1080.0 / 412.0);
    cam.flags |= style::camera_flag::rest;
    auto made = make_scene(*session, *cache, config, cam.width_px, cam.height_px, bundled_packs(dir), 0x808080);
    ATLAS_REQUIRE(made.has_value());
    Scene& s = *made;
    const auto before = draw(s, cam, sunny());
    ATLAS_REQUIRE(before.has_value());
    REQUIRE(s.settled);
    const std::uint32_t me = s.buildings->index();
    std::size_t built = 0;
    std::uint64_t bytes = 0; // the heaps' bytes of their buildings
    for (const std::shared_ptr<const scene::ResidentTile>& t : s.world->residency().resident()) {
        if (!scene::Residency::among(t->built, me)) continue;
        ++built;
        for (const scene::HeapRange& r : t->passes[me].ranges) bytes += r ? r.size : 0U;
    }
    // left out one frame: every resident tile lets go of its buildings
    (void)parts_frame(s, cam, sunny(), style::camera_part::buildings);
    for (const std::shared_ptr<const scene::ResidentTile>& t : s.world->residency().resident()) {
        CHECK_FALSE(scene::Residency::among(t->built, me));
        CHECK(t->passes[me].cpu == nullptr);
    }
    // drawn again: built anew onto each tile drawn (its rows and its light written into its new range), faded in, and
    // the picture as it was
    const auto after = draw(s, cam, sunny());
    ATLAS_REQUIRE(after.has_value());
    CHECK(s.settled);
    for (const scene::CoverTile& t : s.world->cover()->tiles()) CHECK(scene::Residency::among(t.drawn->built, me));
    REQUIRE(after->rgba.size() == before->rgba.size());
    std::size_t differ = 0;
    for (std::size_t i = 0; i + 3 < before->rgba.size(); i += 4) {
        if (!std::equal(before->rgba.begin() + static_cast<std::ptrdiff_t>(i),
                        before->rgba.begin() + static_cast<std::ptrdiff_t>(i + 4),
                        after->rgba.begin() + static_cast<std::ptrdiff_t>(i)))
            ++differ;
    }
    CHECK(differ == 0);
    MESSAGE(std::format("the Westerkerk home: {} tiles let go of their buildings ({:.1f} MB of the heaps), {} drawn "
                        "again with them, {} pixels of {} other than before",
                        built, static_cast<double>(bytes) / 1.0e6, s.world->cover()->tiles().size(), differ,
                        before->rgba.size() / 4));
}

TEST_CASE("buildings: the building the app asks about drawn plain in its flat colour, hatched before its answer") {
    // the synthetic tile's building under the view's middle asked about (FillIn's question, frame/Asked.hpp; picked as
    // a long press picks it, scene/Pick.hpp), with its parts (the flat house's raised part is keyed by it): before the
    // answer its walls in the board's grey under the board's hatch;
    // after it in the answer's flat colour, unhatched; and once no question is open, the frame as it was, pixel for
    // pixel
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    style::CameraState cam = camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, d);
    cam.flags |= style::camera_flag::rest;
    const gpu::Configuration config{.config = gpu::FrameConfig::x1,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::direct};
    ATLAS_REQUIRE(device.facts().supports(config.config));
    auto made = make_scene(*session, *cache, config, cam.width_px, cam.height_px, packs, 0x808080);
    ATLAS_REQUIRE(made.has_value());
    Scene& s = *made;
    const auto before = draw(s, cam, sunny());
    ATLAS_REQUIRE(before.has_value());
    ATLAS_REQUIRE(s.settled);
    // two frames with `asked` (both frame slots light again), then the last one's pixels
    const auto frames = [&](std::optional<frame::AskedBuilding> asked) {
        for (int n = 0; n < 2; ++n) {
            s.now += 0.25;
            s.builder->set_inputs({.camera = cam,
                                   .theme = sunny(),
                                   .seconds = s.now,
                                   .mode = frame::Mode::structural,
                                   .scene = s.world.get(),
                                   .asked = asked});
            auto report = s.renderer->draw(0);
            ATLAS_REQUIRE_OR_RETURN(std::optional<gpu::Pixels>{}, report.has_value(), report.error().text());
            s.renderer->drain();
        }
        auto pixels = s.output->read(s.output->latest());
        ATLAS_REQUIRE_OR_RETURN(std::optional<gpu::Pixels>{}, pixels.has_value(), pixels.error().text());
        return std::optional<gpu::Pixels>(std::move(*pixels));
    };
    // the pixels of one colour (within a level), and those that differ from `before`
    const auto count = [](const gpu::Pixels& px, std::uint32_t rgb) {
        std::size_t n = 0;
        for (std::size_t i = 0; i + 3 < px.rgba.size(); i += 4) {
            const int r = px.rgba[i] - static_cast<int>((rgb >> 16U) & 0xFFU);
            const int g = px.rgba[i + 1] - static_cast<int>((rgb >> 8U) & 0xFFU);
            const int b = px.rgba[i + 2] - static_cast<int>(rgb & 0xFFU);
            if (std::abs(r) <= 1 && std::abs(g) <= 1 && std::abs(b) <= 1) ++n;
        }
        return n;
    };
    const auto changed = [&](const gpu::Pixels& px) {
        std::size_t n = 0;
        for (std::size_t i = 0; i < px.rgba.size() && i < before->rgba.size(); ++i)
            n += px.rgba[i] != before->rgba[i] ? 1 : 0;
        return n;
    };
    constexpr std::uint32_t grey = 0xA2A5A9;
    constexpr std::uint32_t hatch = 0x8C857A;
    constexpr std::uint32_t brown = 0x453433;
    // the building under the view's middle (scene/Pick.hpp), the one asked about
    const frame::FrameState& state = s.builder->state();
    ATLAS_REQUIRE(state.view.has_value());
    ATLAS_REQUIRE(state.ground_frame.has_value());
    const view::View& view = *state.view;
    const view::GroundFrame& ground = *state.ground_frame;
    std::vector<scene::PickPack> drawn;
    for (const auto& tile : s.world->residency().resident()) {
        if (tile != nullptr) drawn.push_back({.id = tile->pack, .z = tile->key.z});
    }
    std::optional<scene::Picked> picked;
    // a grid of points over the view, a row at a time from the top: tenths down, sixths across
    for (int row = 1; row < 10 && !picked; ++row) {
        for (int col = 1; col < 6 && !picked; ++col) {
            const double x = static_cast<double>(cam.width_px) * col / 6.0;
            const double y = static_cast<double>(cam.height_px) * row / 10.0;
            picked =
                scene::pick_building(s.world->store(), drawn, ground, view.camera().eye(), scene::ray_of(view, x, y));
        }
    }
    ATLAS_REQUIRE(picked.has_value());
    const std::int64_t id = picked->id;
    MESSAGE(std::format("asked about building {}", id));
    // its items: the outline's own and, for the flat house, the raised part standing in it, keyed by its outline
    const std::uint64_t key = scene::owner_key(scene::owner_kind_area, static_cast<std::uint64_t>(id));
    const scene::ItemTable& items = *s.world->items();
    std::size_t outline_rows = 0;
    std::size_t part_rows = 0;
    for (std::uint32_t row = 0; row < items.rows(); ++row) {
        if (!items.alive(row) || items.building()[row] != key) continue;
        if (items.owner()[row] == key) {
            ++outline_rows;
        } else {
            ++part_rows;
        }
    }
    CHECK(outline_rows >= 1);
    if (id == 100) CHECK(part_rows >= 1);
    const auto open = frames(frame::AskedBuilding{.id = id, .wall = grey, .hatched = true});
    ATLAS_REQUIRE(open.has_value());
    const auto answered = frames(frame::AskedBuilding{.id = id, .wall = brown, .hatched = false});
    ATLAS_REQUIRE(answered.has_value());
    const auto closed = frames(std::nullopt);
    ATLAS_REQUIRE(closed.has_value());
    if (std::getenv("ATLAS_ASKED_PPM") != nullptr) {           // NOLINT: the frames, to look at
        const std::string out(std::getenv("ATLAS_ASKED_PPM")); // NOLINT
        CHECK(gpu::write_ppm(*before, out + "/asked-before.ppm"));
        CHECK(gpu::write_ppm(*open, out + "/asked-open.ppm"));
        CHECK(gpu::write_ppm(*answered, out + "/asked-answered.ppm"));
    }
    MESSAGE(std::format("asked, open: {} changed, {} grey, {} hatch; answered: {} changed, {} brown, {} hatch; "
                        "closed: {} changed",
                        changed(*open), count(*open, grey), count(*open, hatch), changed(*answered),
                        count(*answered, brown), count(*answered, hatch), changed(*closed)));
    // open: its walls grey between the hatch's lines (structural mode: no grain over them)
    CHECK(changed(*open) > 1000);
    CHECK(count(*open, grey) > 300);
    CHECK(count(*open, hatch) > 300);
    CHECK(count(*before, hatch) < 20);
    // answered: its walls in the answer's flat colour, the hatch gone
    CHECK(count(*answered, brown) > 1000);
    CHECK(count(*answered, hatch) < 20);
    // closed: the frame as it was
    CHECK(changed(*closed) == 0);
    s.renderer.reset();
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: the building the app asks about as the board's house under the roof asked about, in its place") {
    // the synthetic tile's building under the view's middle asked about in a roof (FillIn's roof question,
    // scene/House.hpp, passes/House.hpp): the tile's own building takes no rank, and its house stands in its place: the
    // plain box under its white top while the roof's question is open, its walls as the asked building's are (the
    // board's grey under the hatch, then the answer's colour); under a gabled and a hipped roof its slopes in the
    // board's greys by the sun, its gables in the walls' colour; its walls in its tile's own colour where their colour
    // is not asked about; and once no question is open, the frame as it was, pixel for pixel
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    style::CameraState cam = camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, d);
    cam.flags |= style::camera_flag::rest;
    const gpu::Configuration config{.config = gpu::FrameConfig::x1,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::direct};
    ATLAS_REQUIRE(device.facts().supports(config.config));
    auto made = make_scene(*session, *cache, config, cam.width_px, cam.height_px, packs, 0x808080);
    ATLAS_REQUIRE(made.has_value());
    Scene& s = *made;
    const auto before = draw(s, cam, sunny());
    ATLAS_REQUIRE(before.has_value());
    ATLAS_REQUIRE(s.settled);
    // two frames with `asked` and its house (both frame slots), then the last one's pixels
    const auto frames = [&](std::optional<frame::AskedBuilding> asked, const scene::House* house) {
        for (int n = 0; n < 2; ++n) {
            s.now += 0.25;
            s.builder->set_inputs({.camera = cam,
                                   .theme = sunny(),
                                   .seconds = s.now,
                                   .mode = frame::Mode::structural,
                                   .scene = s.world.get(),
                                   .asked = asked,
                                   .house = house});
            auto report = s.renderer->draw(0);
            ATLAS_REQUIRE_OR_RETURN(std::optional<gpu::Pixels>{}, report.has_value(), report.error().text());
            s.renderer->drain();
        }
        auto pixels = s.output->read(s.output->latest());
        ATLAS_REQUIRE_OR_RETURN(std::optional<gpu::Pixels>{}, pixels.has_value(), pixels.error().text());
        return std::optional<gpu::Pixels>(std::move(*pixels));
    };
    const auto count = [](const gpu::Pixels& px, std::uint32_t rgb) {
        std::size_t n = 0;
        for (std::size_t i = 0; i + 3 < px.rgba.size(); i += 4) {
            const int r = px.rgba[i] - static_cast<int>((rgb >> 16U) & 0xFFU);
            const int g = px.rgba[i + 1] - static_cast<int>((rgb >> 8U) & 0xFFU);
            const int b = px.rgba[i + 2] - static_cast<int>(rgb & 0xFFU);
            if (std::abs(r) <= 1 && std::abs(g) <= 1 && std::abs(b) <= 1) ++n;
        }
        return n;
    };
    const auto changed = [&](const gpu::Pixels& px) {
        std::size_t n = 0;
        for (std::size_t i = 0; i < px.rgba.size() && i < before->rgba.size(); ++i)
            n += px.rgba[i] != before->rgba[i] ? 1 : 0;
        return n;
    };
    constexpr std::uint32_t grey = 0xA2A5A9;
    constexpr std::uint32_t hatch = 0x8C857A;
    constexpr std::uint32_t brown = 0x453433;
    constexpr std::uint32_t white = passes::House::top;
    // the building under the view's middle (scene/Pick.hpp), the one asked about
    const frame::FrameState& state = s.builder->state();
    ATLAS_REQUIRE(state.view.has_value());
    ATLAS_REQUIRE(state.ground_frame.has_value());
    std::vector<scene::PickPack> drawn;
    for (const auto& tile : s.world->residency().resident()) {
        if (tile != nullptr) drawn.push_back({.id = tile->pack, .z = tile->key.z});
    }
    std::optional<scene::Picked> picked;
    for (int row = 1; row < 10 && !picked; ++row) {
        for (int col = 1; col < 6 && !picked; ++col) {
            const double x = static_cast<double>(cam.width_px) * col / 6.0;
            const double y = static_cast<double>(cam.height_px) * row / 10.0;
            picked = scene::pick_building(s.world->store(), drawn, *state.ground_frame, state.view->camera().eye(),
                                          scene::ray_of(*state.view, x, y));
        }
    }
    ATLAS_REQUIRE(picked.has_value());
    const std::int64_t id = picked->id;
    // its house under each roof, read from the tile that draws it
    const scene::ResidentTile* from = scene::house_tile(s.world->residency().resident(), id);
    ATLAS_REQUIRE(from != nullptr);
    CHECK(scene::house_tile(s.world->residency().resident(), 999'999) == nullptr);
    const auto flat = scene::house_of(s.world->store(), *from, id, picked->relation, scene::house_roof(1));
    const auto gabled = scene::house_of(s.world->store(), *from, id, picked->relation, scene::house_roof(2));
    const auto hipped = scene::house_of(s.world->store(), *from, id, picked->relation, scene::house_roof(3));
    ATLAS_REQUIRE(flat != nullptr);
    ATLAS_REQUIRE(gabled != nullptr);
    ATLAS_REQUIRE(hipped != nullptr);
    CHECK(scene::house_roof(0).empty());
    CHECK(scene::house_roof(4).empty());
    CHECK(flat->roof == "flat");
    CHECK(flat->rim.size() >= 4); // its top's outline, closed
    CHECK(gabled->rim.empty());
    CHECK(flat->key == from->key);
    CHECK(flat->pack == from->pack);
    MESSAGE(std::format("asked about building {}: its house {} corners flat, {} gabled, {} hipped; its walls #{:06X}",
                        id, flat->points.size(), gabled->points.size(), hipped->points.size(), flat->wall));
    // the slopes' colours a frame's house draws (the pass's points that are neither walls nor a top nor the outline)
    const auto slopes = [&](std::uint32_t wall) {
        std::vector<std::uint32_t> out;
        const auto points = s.house->points(s.builder->state().slot);
        for (std::uint32_t k = 0; k < s.house->corners(s.builder->state().slot); ++k) {
            const auto& c = points[k].colour;
            const auto level = [](float v) { return static_cast<std::uint32_t>(std::lround(v * 255.0F)); };
            const std::uint32_t rgb = (level(c[0]) << 16U) | (level(c[1]) << 8U) | level(c[2]);
            if (rgb != wall && rgb != white && std::ranges::find(out, rgb) == out.end()) out.push_back(rgb);
        }
        return out;
    };
    // the tile's own building takes no rank while its house is drawn
    const std::uint64_t key = scene::owner_key(scene::owner_kind_area, static_cast<std::uint64_t>(id));
    const auto ranked = [&] {
        std::size_t n = 0;
        const scene::ItemTable& items = *s.world->items();
        const auto table = s.builder->state().ranks->table();
        for (std::uint32_t row = 0; row < items.rows(); ++row) {
            if (items.alive(row) && items.building()[row] == key && table[row] != shaders::RANK_NONE) ++n;
        }
        return n;
    };
    CHECK(ranked() >= 1);
    // the roof's question open, the colour's too: the plain box, grey under the hatch, its top white
    const auto open = frames(frame::AskedBuilding{.id = id, .wall = grey, .hatched = true, .roof = 1}, flat.get());
    ATLAS_REQUIRE(open.has_value());
    CHECK(s.builder->state().house == flat.get());
    CHECK(ranked() == 0);
    CHECK(s.house->corners(s.builder->state().slot) == flat->points.size());
    CHECK(s.house->points(s.builder->state().slot).size() == flat->points.size() + (2 * (flat->rim.size() - 1)));
    CHECK(slopes(grey).empty());
    // the colour answered, the roof gabled: brown walls and gables, two slopes in the board's greys
    const auto pitched =
        frames(frame::AskedBuilding{.id = id, .wall = brown, .hatched = false, .roof = 2}, gabled.get());
    ATLAS_REQUIRE(pitched.has_value());
    const std::vector<std::uint32_t> gabled_slopes = slopes(brown);
    std::size_t gabled_px = 0;
    for (const std::uint32_t c : gabled_slopes) gabled_px += count(*pitched, c);
    // hipped: four faces, by the sun
    const auto hips = frames(frame::AskedBuilding{.id = id, .wall = brown, .hatched = false, .roof = 3}, hipped.get());
    ATLAS_REQUIRE(hips.has_value());
    const std::vector<std::uint32_t> hipped_slopes = slopes(brown);
    std::size_t hipped_px = 0;
    for (const std::uint32_t c : hipped_slopes) hipped_px += count(*hips, c);
    // its walls' colour not asked about: the colour its tile gives them, flat
    const auto own = frames(frame::AskedBuilding{.id = id, .roof = 1, .own_wall = true}, flat.get());
    ATLAS_REQUIRE(own.has_value());
    CHECK(!s.builder->state().asked.has_value());
    CHECK(s.builder->state().house_wall == flat->wall);
    // a house of another building than the one asked about is not drawn, nor one without a question
    const auto other =
        frames(frame::AskedBuilding{.id = id + 1000, .wall = grey, .hatched = true, .roof = 1}, flat.get());
    ATLAS_REQUIRE(other.has_value());
    CHECK(s.builder->state().house == nullptr);
    const auto closed = frames(std::nullopt, flat.get());
    ATLAS_REQUIRE(closed.has_value());
    CHECK(s.builder->state().house == nullptr);
    CHECK(ranked() >= 1);
    if (std::getenv("ATLAS_ASKED_PPM") != nullptr) {           // NOLINT: the frames, to look at
        const std::string out(std::getenv("ATLAS_ASKED_PPM")); // NOLINT
        CHECK(gpu::write_ppm(*open, out + "/house-open.ppm"));
        CHECK(gpu::write_ppm(*pitched, out + "/house-gabled.ppm"));
        CHECK(gpu::write_ppm(*hips, out + "/house-hipped.ppm"));
        CHECK(gpu::write_ppm(*own, out + "/house-own.ppm"));
    }
    MESSAGE(std::format("house, open: {} changed, {} grey, {} hatch, {} white ({} before); gabled: {} brown, {} slope "
                        "colours over {} px; hipped: {} slope colours over {} px; its own walls: {} px; closed: {} "
                        "changed",
                        changed(*open), count(*open, grey), count(*open, hatch), count(*open, white),
                        count(*before, white), count(*pitched, brown), gabled_slopes.size(), gabled_px,
                        hipped_slopes.size(), hipped_px, count(*own, flat->wall), changed(*closed)));
    // open: the box's walls grey between the hatch's lines, its top white
    CHECK(changed(*open) > 1000);
    CHECK(count(*open, grey) > 300);
    CHECK(count(*open, hatch) > 300);
    CHECK(count(*open, white) > count(*before, white) + 300);
    // gabled: brown walls and gables, no hatch, no white top, its slopes drawn
    CHECK(count(*pitched, brown) > 1000);
    CHECK(count(*pitched, hatch) < 20);
    CHECK(count(*pitched, white) <= count(*before, white) + 20);
    CHECK(!gabled_slopes.empty());
    CHECK(gabled_slopes.size() <= 2);
    CHECK(gabled_px > 300);
    for (const std::uint32_t c : gabled_slopes) {
        // each a grey of the board's ramp, from a slope turned away to one square to the sun
        CHECK(((c >> 16U) & 0xFFU) >= ((passes::House::slope_shade >> 16U) & 0xFFU));
        CHECK(((c >> 16U) & 0xFFU) <= ((passes::House::slope_sun >> 16U) & 0xFFU));
    }
    // hipped: more faces than a gabled roof's slopes, lit apart
    CHECK(hipped_slopes.size() >= 2);
    CHECK(hipped_px > 300);
    // its own walls
    CHECK(count(*own, flat->wall) > 300);
    CHECK(count(*own, hatch) < 20);
    // another building's house, and none: the frame as it was but for the asked look of the other building (none)
    CHECK(changed(*closed) == 0);
    s.renderer.reset();
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: the Westerkerk home drawn on the host's Vulkan, pixel for pixel the exact shapes of its twin") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Westerkerk home is not drawn here");
        return;
    }
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home", bundled_packs(dir), sunny(), 1000);
}

TEST_CASE(
    "buildings: the Westerkerk home at night drawn on the host's Vulkan, the shop floors' glazing lit where one of "
    "their places is open and dark where none is, pixel for pixel the exact shapes of its twin") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Westerkerk home is not drawn here");
        return;
    }
    // Thursday 1 October 2026 at 21:30 (amsterdam.py's open_here): the cafés and bars open, the shops closed (of the
    // 318 glazings in the view, 17 lit and 301 dark)
    style::ThemeBlock night = sunny();
    night.flags |= style::theme_flag::night;
    night.hour = 21;
    night.minute = 30;
    const Glazings both{.open = 12, .closed = 250};
    check_frames(home(1080, 2400, 1080.0 / 412.0), "home at night", bundled_packs(dir), night, 1000, nullptr, false, 0,
                 nullptr, &both);
}

TEST_CASE("buildings: the Main board drawn on the host's Vulkan, pixel for pixel the exact shapes of its twin") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Main board is not drawn here");
        return;
    }
    const auto cam = region_view(dir, "grachten_home", 1080, 2400, 1080.0 / 412.0);
    ATLAS_REQUIRE(cam.has_value(), "the grachten pack has no grachten_home view");
    // the canal ring's pack alone, as the board draws it
    const std::array<PackFile, 1> packs{PackFile{.id = "grachten", .path = dir + "/grachten.atlas"}};
    style::ThemeBlock later = sunny();
    later.hour = 17;
    later.sun = {.r = 0xFF, .g = 0xD9, .b = 0xA8, .a = 255};
    later.sun_k = 0.9;
    check_frames(*cam, "board", packs, sunny(), 1000, &later);
}

TEST_CASE("buildings: a pair the renders melt drawn by true depth as their geometry says, and by the painter switch as "
          "the renders paint them") {
    // fixture.hpp's melt_tile: the front bar of a long wing hides a tower's foot; the renders paint the tower over it
    const std::string path = synthetic_pack(test::building::melt_tile(), test::building::melt_features, "melt");
    const std::array<PackFile, 1> packs{PackFile{.id = "melt", .path = path}};
    const style::CameraState cam = camera(52.37425, 4.88425, 0.0, 70.0, 300.0, 1080, 2400, 1080.0 / 412.0);
    check_frames(cam, "melting pair", packs, sunny(), 4);
    check_frames(cam, "melting pair", packs, sunny(), 4, nullptr, true);
    // the two orders differ where the tower's foot lies behind the bar: drawn by true depth the bar covers it
    auto session = test::open_session();
    if (session) {
        const gpu::Device& device = *session->device;
        auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        const gpu::Configuration x1{.config = gpu::FrameConfig::x1,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::direct};
        style::CameraState c = cam;
        c.flags |= style::camera_flag::rest;
        std::array<std::optional<gpu::Pixels>, 2> frames;
        for (std::size_t mode = 0; mode < 2; ++mode) {
            auto made = make_scene(*session, *cache, x1, c.width_px, c.height_px, packs, 0x808080);
            ATLAS_REQUIRE(made.has_value());
            made->builder->set_painter(mode == 1);
            frames[mode] = draw(*made, c, sunny());
            ATLAS_REQUIRE(frames[mode].has_value());
            made->renderer.reset();
        }
        const std::optional<gpu::Pixels>& depth = frames[0];
        const std::optional<gpu::Pixels>& painter = frames[1];
        ATLAS_REQUIRE(depth.has_value() && painter.has_value());
        std::size_t differ = 0;
        for (std::size_t i = 0; i + 3 < depth->rgba.size(); i += 4) {
            if (!std::equal(depth->rgba.begin() + static_cast<std::ptrdiff_t>(i),
                            depth->rgba.begin() + static_cast<std::ptrdiff_t>(i + 3),
                            painter->rgba.begin() + static_cast<std::ptrdiff_t>(i))) {
                ++differ;
            }
        }
        MESSAGE("the melting pair: " << differ << " pixels differ between true depth and the renders' painter's order");
        CHECK(differ > 2000);
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    }
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: a new moment lit ahead on the pass's own thread: every frame shows one moment whole, the new one "
          "from the frame that takes it on, both frame slots, as a frame drawn from scratch at it") {
    // the synthetic tile in a render pack of its own (so CI draws it without the bundled packs), at the Main board's
    // change of moment: the sun's colour and strength of five o'clock, its place the same (so are the sun bits)
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    style::ThemeBlock later = sunny();
    later.hour = 17;
    later.sun = {.r = 0xFF, .g = 0xD9, .b = 0xA8, .a = 255};
    later.sun_k = 0.9;
    check_moments(camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, 1080.0 / 412.0), "synthetic near", packs,
                  sunny(), later);
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE(
    "buildings: a new moment at the Main board lit ahead on the pass's own thread, and the render thread's frames "
    "around it") {
    const std::string dir = environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the Main board is not drawn here");
        return;
    }
    const auto cam = region_view(dir, "grachten_home", 1080, 2400, 1080.0 / 412.0);
    ATLAS_REQUIRE(cam.has_value(), "the grachten pack has no grachten_home view");
    // the canal ring's pack alone, as the board draws it, and its moment change above
    const std::array<PackFile, 1> packs{PackFile{.id = "grachten", .path = dir + "/grachten.atlas"}};
    style::ThemeBlock later = sunny();
    later.hour = 17;
    later.sun = {.r = 0xFF, .g = 0xD9, .b = 0xA8, .a = 255};
    later.sun_k = 0.9;
    check_moments(*cam, "board", packs, sunny(), later);
}

TEST_CASE(
    "buildings: a level's lean records drawn on the host's Vulkan, pixel for pixel the exact shapes of its twin") {
    // the synthetic tile built as a level's tile is (ENGINE.md, one pack per country, step 7): each wall its one cell,
    // no windowed walls, each primitive's corners and its face's test point in steps of the tile's grid, near and far
    // and under the painter switch, so every stage that reads the records (the walls, the roofs, their thin edges, the
    // cull) reads what the twin reads
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    lean_tiles = true;
    check_frames(camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, d), "synthetic near, lean", packs, sunny(),
                 10);
    check_frames(camera(52.37422, 4.88486, 300.0, 45.0, 650.0, 1080, 2400, d), "synthetic far, lean", packs, sunny(),
                 10);
    check_frames(camera(52.37422, 4.88486, 20.0, 50.0, 260.0, 1080, 2400, d), "synthetic near, lean", packs, sunny(),
                 10, nullptr, true);
    lean_tiles = false;
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: in the app's map a level's tile's walls narrower on the screen than 2.5 device px are drawn at "
          "their share of each pixel, with as much of their colour as on the samples, through a tile's fade too; a "
          "finest tile's are as they were") {
    // the synthetic tile from 9 km at 45°, where a 9 m wall is under 2 px tall: built as a level's tile is, in the
    // app's map, at 4 samples a pixel, with the thin walls' draws and without, and without its walls; then built as
    // a finest tile is, whose walls stay on the samples whatever their width
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    if (!device.facts().supports(config.config)) {
        MESSAGE("the device has no frame of 4 samples a pixel: the thin walls are not drawn here");
        return;
    }
    const std::string path = synthetic_pack();
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    style::CameraState cam = camera(52.37422, 4.88486, 300.0, 45.0, 9000.0, 1080, 2400, d);
    cam.flags &= ~style::camera_flag::engine_mode;
    cam.flags |= style::camera_flag::rest;
    constexpr std::uint32_t clear = 0x808080;
    struct Drawn {
        gpu::Pixels pixels;
        std::uint32_t chunks = 0;
    };
    const auto frame_of = [&](bool lean, bool thin, std::uint64_t parts_off,
                              std::optional<std::array<float, 2>> fade = std::nullopt) -> std::optional<Drawn> {
        lean_tiles = lean;
        app_map = true;
        auto made = make_scene(*session, *cache, config, cam.width_px, cam.height_px, packs, clear);
        lean_tiles = false;
        app_map = false;
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, made.has_value());
        made->buildings->draw_thin_walls(thin);
        made->buildings->fade_every(fade);
        style::CameraState c = cam;
        c.parts |= parts_off;
        auto pixels = draw(*made, c, sunny());
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, pixels.has_value());
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, made->settled);
        CHECK(made->builder->state().grounded);
        Drawn out{.pixels = std::move(*pixels), .chunks = made->buildings->thin_wall_chunks()};
        made->renderer.reset();
        return out;
    };
    const auto thin = frame_of(true, true, 0);
    const auto sampled = frame_of(true, false, 0);
    const auto bare = frame_of(true, false, style::camera_part::walls);
    ATLAS_REQUIRE(thin.has_value());
    ATLAS_REQUIRE(sampled.has_value());
    ATLAS_REQUIRE(bare.has_value());
    CHECK(thin->chunks > 0);
    CHECK(sampled->chunks == 0);
    // the walls' colour over the frame: each channel's sum against the frame without walls
    const auto sums = [](const gpu::Pixels& p) {
        std::array<double, 3> out{};
        for (std::size_t i = 0; i < p.rgba.size(); i += 4) {
            for (std::size_t k = 0; k < 3; ++k) out[k] += p.rgba[i + k];
        }
        return out;
    };
    const std::array<double, 3> a = sums(thin->pixels);
    const std::array<double, 3> b = sums(sampled->pixels);
    const std::array<double, 3> none = sums(bare->pixels);
    std::size_t changed = 0;
    for (std::size_t i = 0; i < thin->pixels.rgba.size(); i += 4) {
        changed += !std::equal(thin->pixels.rgba.begin() + static_cast<std::ptrdiff_t>(i),
                               thin->pixels.rgba.begin() + static_cast<std::ptrdiff_t>(i + 3),
                               sampled->pixels.rgba.begin() + static_cast<std::ptrdiff_t>(i))
                       ? 1
                       : 0;
    }
    double walls = 0;
    double off = 0;
    for (std::size_t k = 0; k < 3; ++k) {
        walls += std::abs(b[k] - none[k]);
        off += std::abs(a[k] - b[k]);
    }
    MESSAGE(std::format("from 9 km: the thin walls' draws over {} chunks change {} pixels; the walls' colour sums to "
                        "{:.0f} levels over the frame without them on the samples, the thin walls' draws {:.0f} off "
                        "({:.1f} %)",
                        thin->chunks, changed, walls, off, walls > 0 ? 100.0 * off / walls : 0.0));
    CHECK(changed > 20);
    CHECK(walls > 1000.0);
    CHECK(off < 0.1 * walls);
    // while the tile fades (every tile's standing fade set, as the cover sets one fading in): half way the thin
    // walls hold the pixels the dither keeps, as the samples' do, with as much of the walls' colour as on the samples
    // there and about half of what they hold at rest; at the fade's start nothing is left of them
    const std::array<float, 2> half{0.0F, 0.5F};
    const std::array<float, 2> out{0.0F, 0.0F};
    const auto thin_half = frame_of(true, true, 0, half);
    const auto sampled_half = frame_of(true, false, 0, half);
    const auto bare_half = frame_of(true, false, style::camera_part::walls, half);
    const auto thin_out = frame_of(true, true, 0, out);
    const auto sampled_out = frame_of(true, false, 0, out);
    const auto bare_out = frame_of(true, false, style::camera_part::walls, out);
    ATLAS_REQUIRE(thin_half.has_value());
    ATLAS_REQUIRE(sampled_half.has_value());
    ATLAS_REQUIRE(bare_half.has_value());
    ATLAS_REQUIRE(thin_out.has_value());
    ATLAS_REQUIRE(sampled_out.has_value());
    ATLAS_REQUIRE(bare_out.has_value());
    const std::array<double, 3> ah = sums(thin_half->pixels);
    const std::array<double, 3> bh = sums(sampled_half->pixels);
    const std::array<double, 3> none_half = sums(bare_half->pixels);
    double walls_half = 0;
    double thin_half_walls = 0;
    double off_half = 0;
    double thin_walls = 0;
    for (std::size_t k = 0; k < 3; ++k) {
        walls_half += std::abs(bh[k] - none_half[k]);
        thin_half_walls += std::abs(ah[k] - none_half[k]);
        off_half += std::abs(ah[k] - bh[k]);
        thin_walls += std::abs(a[k] - none[k]);
    }
    MESSAGE(std::format("half way through a fade: the walls' colour sums to {:.0f} levels on the samples, the thin "
                        "walls' draws {:.0f} off ({:.1f} %), {:.2f} of their sum at rest",
                        walls_half, off_half, walls_half > 0 ? 100.0 * off_half / walls_half : 0.0,
                        thin_walls > 0 ? thin_half_walls / thin_walls : 0.0));
    CHECK(thin_half->chunks > 0);
    CHECK(walls_half > 500.0);
    CHECK(off_half < 0.1 * walls_half);
    CHECK(thin_half_walls > 0.35 * thin_walls);
    CHECK(thin_half_walls < 0.65 * thin_walls);
    CHECK_MESSAGE(thin_out->pixels.rgba == sampled_out->pixels.rgba, "a thin wall left at its tile's fade's start");
    CHECK_MESSAGE(thin_out->pixels.rgba == bare_out->pixels.rgba, "a wall left at its tile's fade's start");
    // a finest tile's walls: no thin walls' draws, the same pixels with the switch and without
    const auto fine = frame_of(false, true, 0);
    const auto fine_sampled = frame_of(false, false, 0);
    ATLAS_REQUIRE(fine.has_value());
    ATLAS_REQUIRE(fine_sampled.has_value());
    CHECK(fine->chunks == 0);
    CHECK_MESSAGE(fine->pixels.rgba == fine_sampled->pixels.rgba, "a finest tile's walls changed");
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

TEST_CASE("buildings: a guessed roof's shape drawn as the plain box in its place while the user's palette has Atlas's "
          "guesses off, pixel for pixel the exact shapes of their twin") {
    // the synthetic tile with each pitched roof's shape guessed (its `guessed` list naming roof:shape): with the
    // guesses off (FRAME_PLAIN_ROOFS) each is drawn as its walls up to the roof's top under a flat top, from the south
    // (near: the walls' top cells raised), far (each wall one cell), far as a level's lean records (the roof's top on
    // the tile's grid) and straight down, the GPU's pixels the twin's; from the south each house's walls reach higher
    // with the guesses off than with them on; straight down the twin draws the hipped roof's ends and the pyramid's
    // faces as triangles with the guesses on, and with them off no triangle of the five, each roof a flat top
    test::TileBytes t = test::building::tile();
    const std::vector<std::string> shape{std::string(tiles::building::guessed_roof_shape)};
    t.layers[0].columns.push_back(
        {"guessed", tiles::Codec::strs,
         test::columns::strs(t, {std::nullopt, shape, shape, shape, shape, shape, std::nullopt})});
    const std::string path = synthetic_pack(std::move(t), test::building::tile_features, "guessed");
    const std::array<PackFile, 1> packs{PackFile{.id = "synthetic", .path = path}};
    const double d = 1080.0 / 412.0;
    // the five guessed houses' row (20 to 117 m east of the tile's corner, 0 to 20 m north) in view about the frame's
    // origin (52.3746° N, 4.884° E, 46 to 66 m north of the row), which a synthetic pack's frames are drawn about
    // wherever the camera is (its head's one view names no target, so the pack has no ladder): looking north-west at
    // 50° from 400 m, the houses 284 to 363 m away; at 45° from 650 m, 540 to 616 m away; straight down from 460 m
    const style::CameraState south = camera(52.3746, 4.884, 308.0, 50.0, 400.0, 1080, 2400, d);
    const style::CameraState far = camera(52.3746, 4.884, 308.0, 45.0, 650.0, 1080, 2400, d);
    const style::CameraState down = camera(52.3746, 4.884, 312.0, 0.0, 460.0, 1080, 2400, d);
    style::PaletteBlock plain{};
    plain.flags = style::palette_flag::no_guesses;
    {
        const WithPalette with(&plain);
        check_frames(south, "guessed shapes from the south, guesses off", packs, sunny(), 15);
        check_frames(far, "guessed shapes far, guesses off", packs, sunny(), 15);
        lean_tiles = true;
        check_frames(far, "guessed shapes far, lean, guesses off", packs, sunny(), 10);
        lean_tiles = false;
        check_frames(down, "guessed shapes straight down, guesses off", packs, sunny(), 20);
    }
    auto session = test::open_session();
    if (session) {
        const gpu::Device& device = *session->device;
        auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        const gpu::Configuration config{.config = gpu::FrameConfig::x1,
                                        .route = gpu::Route::renderpass,
                                        .form = gpu::FrameForm::composite,
                                        .target = gpu::CompositeTarget::direct};
        ATLAS_REQUIRE(device.facts().supports(config.config));
        // the twin's elements of each guessed building (ids 101 to 105), its closed paths of three points among them,
        // and the topmost point (CSS px) of its walls' (each filled and edged in one colour, 0.3 px wide)
        constexpr float none = std::numeric_limits<float>::infinity();
        struct Shapes {
            std::array<std::size_t, 5> elements{};
            std::size_t triangles = 0;
            std::array<float, 5> wall_top{none, none, none, none, none};
        };
        const auto shapes = [&](style::CameraState cam, const style::PaletteBlock* palette) {
            const WithPalette with(palette);
            cam.flags |= style::camera_flag::rest;
            Shapes out;
            auto made = make_scene(*session, *cache, config, cam.width_px, cam.height_px, packs, 0x808080);
            ATLAS_REQUIRE_OR_RETURN(out, made.has_value());
            Scene& s = *made;
            const auto pixels = draw(s, cam, sunny());
            ATLAS_REQUIRE_OR_RETURN(out, pixels.has_value());
            CHECK(s.settled);
            twin::Stream stream;
            s.buildings->emit(s.builder->state(), stream, passes::Buildings::draw_phase);
            const twin::Decoded decoded = twin::decode(stream.bytes());
            ATLAS_REQUIRE_OR_RETURN(out, decoded.error.empty(), decoded.error);
            for (const twin::Element& e : decoded.document.elements) {
                if (e.item < 0 || e.clip != twin::none || e.kind != twin::Op::path) continue;
                const std::int64_t id = decoded.document.items[static_cast<std::size_t>(e.item)].id;
                if (id < 101 || id > 105) continue;
                const auto k = static_cast<std::size_t>(id - 101);
                ++out.elements.at(k);
                const bool closed = !e.closed.empty() && e.closed.front() != 0;
                if (e.points.size() == 3 && closed) ++out.triangles;
                if (std::abs(e.style.stroke_width - 0.3F) < 1e-6F) {
                    for (const twin::Point& q : e.points) out.wall_top.at(k) = std::min(out.wall_top.at(k), q.y);
                }
            }
            s.renderer.reset();
            return out;
        };
        const auto listed = [](const auto& n) { return std::format("{} {} {} {} {}", n[0], n[1], n[2], n[3], n[4]); };
        const Shapes on = shapes(down, nullptr);
        const Shapes off = shapes(down, &plain);
        MESSAGE(std::format("straight down, the guessed buildings' elements {} with the guesses on ({} triangles), {} "
                            "with them off ({} triangles)",
                            listed(on.elements), on.triangles, listed(off.elements), off.triangles));
        CHECK(on.triangles >= 6);
        CHECK(off.triangles == 0);
        for (std::size_t k = 0; k < on.elements.size(); ++k) {
            CAPTURE(k);
            CHECK(on.elements.at(k) > 0);
            CHECK(off.elements.at(k) > 0);
        }
        const Shapes south_on = shapes(south, nullptr);
        const Shapes south_off = shapes(south, &plain);
        MESSAGE(
            std::format("from the south, the guessed buildings' walls' tops at {} px with the guesses on, {} px with "
                        "them off",
                        listed(south_on.wall_top), listed(south_off.wall_top)));
        std::size_t higher = 0;
        for (std::size_t k = 0; k < south_on.wall_top.size(); ++k) {
            CAPTURE(k);
            REQUIRE(south_on.wall_top.at(k) < none);
            REQUIRE(south_off.wall_top.at(k) < none);
            CHECK(south_off.wall_top.at(k) <= south_on.wall_top.at(k));
            if (south_off.wall_top.at(k) < south_on.wall_top.at(k) - 2.0F) ++higher;
        }
        CHECK(higher >= 4);
    }
    std::error_code ignored;
    std::filesystem::remove(path, ignored);
}

#endif
