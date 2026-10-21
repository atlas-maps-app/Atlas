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
// in front of it) is drawn by true depth as its geometry says and by the painter switch as the renders paint it. The
// validation layer stays silent.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Composite.hpp"
#include "passes/Walls.hpp"
#include "platform/host/Offscreen.hpp"
#include "scene/SunGlobals.hpp"
#include "scene/World.hpp"
#include "style/Theme.hpp"
#include "sun/Buckets.hpp"
#include "tests/building/fixture.hpp"
#include "tests/fx/stroke_area.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Container.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <format>
#include <memory>
#include <span>
#include <string>
#include <string_view>
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
    /// An n-gon (five corners or more): the GPU fills the triangles stage T cut from its ring in the plan, which at
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
/// plane's within the shape's span; a plain one's fan of 3 over its corners; an n-gon's triangles, each over its own
/// corners.
[[nodiscard]] std::vector<std::array<Vertex, 3>> triangles_of(const shaders::FrameCamera& cam,
                                                              const twin::ElementDepth& e, float half) {
    namespace sh = atlas::shaders;
    std::vector<Vertex> fan;
    std::vector<std::array<Vertex, 3>> out;
    const auto plain_fan = [&](const sh::BuildingShape& s) {
        fan.clear();
        for (std::uint32_t v = 0; v < sh::BUILDING_PLAIN_VERTICES; ++v) {
            const hlsl::float3 p = sh::building_plain_vertex(s, v);
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
    } else if (!e.triangles.empty()) {
        for (const sh::BuildingShape& t : e.triangles) plain_fan(t);
    } else {
        plain_fan(e.shape);
    }
    return out;
}

/// A shape's slack (Shape::slack): 2^-18 (the rasteriser's rounding of a long thin triangle's depth, against the
/// twin's: lavapipe's strays some twenty units of the last place), and for an edged one 2^-18 times its face plane's
/// condition at its outline's points (the sum of dot(n, ray)'s terms' magnitudes over its own).
[[nodiscard]] double slack_of(const shaders::FrameCamera& cam, const twin::ElementDepth& e,
                              std::span<const std::array<Vertex, 3>> triangles) {
    constexpr double base = 3.814697265625e-6; // 2^-18
    if (!e.edged) return base;
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

/// The document's shapes, each with its depth from `depths` (the pass's, in the stream's order) at frame camera `cam`,
/// a wall's stroke `wall` CSS px wide (the frame's strokes').
[[nodiscard]] std::vector<Shape> shapes_of(const twin::Document& doc, double density,
                                           std::span<const twin::ElementDepth> depths, const shaders::FrameCamera& cam,
                                           float wall) {
    std::vector<Shape> out;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->points.size() < 3) continue;
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
                g.depth[q] = sh::building_stroke_depth(cam, s.slot, sh::building_stroke_recip(oa, ob, oc, here));
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
            if (ed.triangles.empty()) {
                add(ed.shape, e->style.stroke_width, colour);
            } else {
                for (const sh::BuildingShape& tri : ed.triangles) add(tri, e->style.stroke_width, colour);
            }
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

/// The raster `r` of `shapes` (its samples at `samples` of each pixel of w × h) with the strokes `segs` blended over it
/// in their order, as the frame's two pipelines of them blend (structural mode: alpha a, the stroke's colour, over the
/// ground and over the objects alike): each pixel's coverage (the shader's, in float) at each of its samples where the
/// stroke's depth passes GREATER_OR_EQUAL against the sample's shape's (by true depth, or under the painter switch by
/// their slots), premultiplied source over, then 8 bits.
[[nodiscard]] Stroked stroke(const Raster& r, std::span<const Shape> shapes, std::span<const StrokeSeg> segs,
                             std::uint32_t w, std::uint32_t h, std::span<const Pt> samples,
                             const shaders::FrameCamera& cam, std::uint32_t clear) {
    const bool painter = (cam.flags.x & shaders::CAMERA_PAINTER) != 0U;
    const std::size_t n = std::size_t{w} * h * samples.size();
    Stroked out;
    out.colour.resize(n);
    out.blends.assign(n, 0);
    out.tie.assign(n, 0);
    for (std::size_t i = 0; i < n; ++i) {
        const std::uint32_t c = r.shape[i] == uncovered ? clear : shapes[static_cast<std::size_t>(r.shape[i])].colour;
        out.colour[i] = {static_cast<std::uint8_t>((c >> 16U) & 0xFFU), static_cast<std::uint8_t>((c >> 8U) & 0xFFU),
                         static_cast<std::uint8_t>(c & 0xFFU)};
    }
    constexpr double rounding = 3.814697265625e-6; // 2^-18: the rasteriser's rounding of the stroke's quad
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
    return out;
}

/// The packs, the world, the builder and the renderer of one test frame.
struct Scene {
    std::unique_ptr<platform::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Buildings* buildings = nullptr;
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

/// Draws `cam` until the world has built every tile the cover wants and their fades are over; the last frame's pixels.
[[nodiscard]] std::optional<platform::Pixels> draw(Scene& s, const style::CameraState& cam,
                                                   const style::ThemeBlock& th) {
    s.builder->set_inputs(
        {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
    int waited = 0;
    for (int n = 0; n < 80; ++n) {
        s.now += 0.25;
        s.builder->set_inputs(
            {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        s.world->residency().wait_idle();
        const scene::Cover& cover = *s.world->cover();
        const bool built = n > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
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
    // one more, with every tile taken and settled
    s.now += 0.25;
    s.builder->set_inputs(
        {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
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

/// Draws `cam` at moment `th` for two frames (both frame slots) once the world has settled; the last frame's pixels.
[[nodiscard]] std::optional<platform::Pixels> redraw(Scene& s, const style::CameraState& cam,
                                                     const style::ThemeBlock& th) {
    for (int n = 0; n < 2; ++n) {
        s.now += 0.25;
        s.builder->set_inputs(
            {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
    }
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
[[nodiscard]] Compared compare(const platform::Pixels& got, const twin::Document& doc,
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
    // the roofs' thin strokes over each of them
    const std::vector<StrokeSeg> segs = strokes_of(doc, depths, distances, cam);
    const Stroked exact_stroked = stroke(exact_raster, shapes, segs, w, h, samples, cam, clear);
    const Stroked grown_stroked = stroke(grown_raster, shapes, segs, w, h, samples, cam, clear);
    const Stroked shrunk_stroked = stroke(shrunk_raster, shapes, segs, w, h, samples, cam, clear);
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
        platform::Pixels ref = got;
        for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
            const std::array<std::uint32_t, 3> want = resolved(exact_stroked, i);
            for (std::size_t k = 0; k < 3; ++k) ref.rgba[(i * 4) + k] = static_cast<std::uint8_t>(want[k]);
        }
        (void)platform::write_ppm(ref, std::format("{}/exact {}.ppm", out, gpu::name_of(config)));
    }
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool covered = false;
        std::uint32_t blends = 0; // the most strokes a sample of the pixel took, in any of the three rasters
        for (std::size_t s = 0; s < n; ++s) {
            const std::size_t k = (i * n) + s;
            covered = covered || exact[k] != uncovered || grown[k] != uncovered;
            blends = std::max({blends, std::uint32_t{exact_stroked.blends[k]}, std::uint32_t{grown_stroked.blends[k]},
                               std::uint32_t{shrunk_stroked.blends[k]}});
        }
        if (!covered && blends == 0) continue;
        ++c.pixels;
        if (blends > 0) ++c.stroked;
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        // a pixel a stroke reaches within a level a blend (the attachment's 8 bits after each, lavapipe blending them
        // in fixed point; and one more where the composite averages samples), the rest exactly
        const std::uint32_t levels = blends + (blends > 0 && n > 1 ? 1U : 0U);
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
        // shapes grow or shrink by it
        bool ambiguous = false;
        bool sliver = false;
        bool tie = false;
        for (std::size_t s = 0; s < n; ++s) {
            const std::size_t k = (i * n) + s;
            ambiguous = ambiguous || exact[k] != grown[k] || exact[k] != shrunk[k];
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

/// Makes the scene for `config` on a w × h output, drawing from `packs`.
[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              std::span<const PackFile> packs, std::uint32_t clear) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = platform::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite, and the buildings pass of its own (the test reads its twin)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Buildings>();
    s.buildings = owned.get();
    passes.push_back(std::move(owned));
    const std::array<float, 4> c{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                 static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                 static_cast<float>(clear & 0xFFU) / 255.0F, 1.0F};
    auto builder =
        frame::FrameBuilder::create(device, cache, std::move(passes), {.overdraw = false, .clear = c, .steps = 0});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, builder.has_value(), builder.error().text());
    s.builder = std::move(*builder);
    auto world = scene::World::create(device, s.builder->passes(), {.workers = 3, .clock = {}});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, world.has_value(), world.error().text());
    s.world = std::move(*world);
    for (const PackFile& pack : packs) {
        auto added = s.world->store().add({.id = pack.id, .kind = tiles::PackKind::bundled, .path = pack.path});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    }
    auto renderer = gpu::Renderer::create(device, *s.output, *s.builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    s.renderer = std::move(*renderer);
    return s;
}

/// Draws `cam` in X1 and C, at rest and moving, at moment `th` over `packs`, and holds every pixel to the exact raster
/// of the pass's twin; the twin emits at least `least` elements. With `then`, the first frame's moment changes to it
/// (the CPU's light lights every drawn tile again) and that frame is held to the twin too. With `painter`, under the
/// painter switch: the renders' order, held to the twin's painter order.
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least, const style::ThemeBlock* then = nullptr,
                  bool painter = false) {
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
            const scene::HeapStats heap = s.world->heaps()->stats();
            const auto walls = static_cast<std::size_t>(scene::HeapKind::walls);
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
            for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
            if (const std::string out = environment("ATLAS_BUILDINGS_PPM"); !out.empty()) {
                CHECK(platform::write_ppm(*pixels, std::format("{}/{}.ppm", out, name)));
            }
            if (then != nullptr && rest && config.config == gpu::FrameConfig::x1) {
                // another moment: every drawn tile's colours again, on the CPU, frame slot by frame slot
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
                MESSAGE(name << ", the moment changed: the CPU lit " << l.tiles << " tiles' slots, " << l.faces
                             << " faces, in " << l.ms << " ms ("
                             << (l.tiles > 0 ? l.ms / static_cast<double>(l.tiles) : 0.0) << " ms a tile, the longest "
                             << l.longest_ms << " ms); " << r2.pixels << " pixels, " << r2.wrong << " wrong");
                CHECK(l.tiles > 0);
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
    check_frames(camera(lat, lon, 300.0, 45.0, 650.0, 1080, 2400, d), "synthetic far", packs, sunny(), 30);
    check_frames(camera(lat, lon, 0.0, 0.0, 220.0, 1080, 2400, d), "synthetic straight down", packs, sunny(), 20);
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
        std::array<std::optional<platform::Pixels>, 2> frames;
        for (std::size_t mode = 0; mode < 2; ++mode) {
            auto made = make_scene(*session, *cache, x1, c.width_px, c.height_px, packs, 0x808080);
            ATLAS_REQUIRE(made.has_value());
            made->builder->set_painter(mode == 1);
            frames[mode] = draw(*made, c, sunny());
            ATLAS_REQUIRE(frames[mode].has_value());
            made->renderer.reset();
        }
        const std::optional<platform::Pixels>& depth = frames[0];
        const std::optional<platform::Pixels>& painter = frames[1];
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

#endif
