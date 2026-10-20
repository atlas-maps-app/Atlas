// The buildings pass on the host's Vulkan (plan-vector D10, K10's L2 on lavapipe): every roof shape and a houseboat
// from a synthetic render pack (tests/building/fixture.hpp's tile, so CI draws them), and the Westerkerk home and the
// Main board from the bundled packs (ATLAS_TILES_DIR), drawn through the frame builder and the world with their sun
// bits (K8's, through set 1's sun_bits), every building's walls, cells and roofs at their painter slots with the
// objects bit, their light dispatched per frame, against an exact CPU raster of what the pass's twin says it draws:
// each element's polygon, a cell's grown by its 0.15 px edge with round joins (the true Minkowski sum, not the vertex
// stage's outline; the roofs' thin edge strokes left out: the objects' thin strokes draw them, K17a's ObjectStrokes,
// which has not landed), in the twin's painter order, sampled where the GPU samples. At one sample a pixel
// (configuration X1) every pixel must be the reference's colour, but where a sample lies within 0.02 px of a shape's
// boundary (the reference drawn 0.02 px larger or smaller ends that sample with another shape); at 4x every pixel's
// samples are averaged as the composite averages them. Pixels near a cell that snapping at rest made concave or crossed
// (a wall seen within a tenth of a pixel of edge-on, which the vertex stage outlines as a convex polygon), or within
// 0.25 px of an n-gon's boundary (whose triangles, cut in the plan, can stray from its snapped ring at rest), are
// counted apart and bounded. The validation layer stays silent.
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
#include <fstream>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <unistd.h>
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

/// The twin's objects that are filled paths, in painter's order, each with its polygon in device px.
struct Shape {
    std::vector<Pt> poly;
    double stroke = 0; ///< a stroke's half width, device px (0: none)
    std::uint32_t colour = 0;
    /// A stroked quad whose corners snapping made concave or crossed: the vertex stage outlines the polygon as a
    /// convex one, so near it the GPU may differ from the exact shape (a wall seen within a tenth of a pixel of
    /// edge-on; counted apart).
    bool sliver = false;
    /// An n-gon (five corners or more): the GPU fills the triangles stage T cut from its ring in the plan, which at
    /// rest (each corner snapped to a tenth of a pixel) can stray from the snapped ring by a fraction of a pixel along
    /// its boundary (counted apart).
    bool ngon = false;
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

[[nodiscard]] std::vector<Shape> shapes_of(const twin::Document& doc, double density) {
    std::vector<Shape> out;
    for (const twin::Element* e : doc.order(twin::Layer::objects)) {
        if (e->kind != twin::Op::path || e->points.size() < 3) continue;
        Shape sh;
        for (const twin::Point& p : e->points) {
            sh.poly.push_back({static_cast<double>(p.x) * density, static_cast<double>(p.y) * density});
        }
        // the cells' same-colour 0.3 px edges, which the pass draws; the roofs' thin edge strokes (.25, .45 and .4 px
        // in other colours) are the objects' thin strokes' (K17a's ObjectStrokes), left out until that pass lands
        const bool stroked =
            e->style.stroke.kind == twin::Paint::Kind::colour && std::abs(e->style.stroke_width - 0.3F) < 1e-6F;
        sh.stroke = stroked ? static_cast<double>(e->style.stroke_width) * density / 2 : 0.0;
        sh.colour = e->style.fill.argb & 0xFF'FFFFU;
        sh.sliver = stroked && turns_both_ways(sh.poly);
        sh.ngon = !stroked && sh.poly.size() >= 5;
        out.push_back(std::move(sh));
    }
    return out;
}

/// No shape covers a sample.
constexpr std::int32_t uncovered = -1;

/// The shapes rasterised at sample positions, each grown by `grow` px (its fill's polygon, a stroke's half width
/// added): the shape each sample ends with in painter order, or `uncovered`. With `slivers`, instead every sample
/// within a pixel of a sliver's shape, or within 0.25 px of an n-gon's boundary.
[[nodiscard]] std::vector<std::int32_t> raster(std::span<const Shape> shapes, std::uint32_t w, std::uint32_t h,
                                               std::span<const Pt> samples, double grow, bool slivers = false) {
    std::vector<std::int32_t> out(std::size_t{w} * h * samples.size(), uncovered);
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
                    const Pt p{static_cast<double>(x) + samples[s].x, static_cast<double>(y) + samples[s].y};
                    const double d = signed_distance(p, sh.poly);
                    const bool in = slivers && !sh.sliver ? std::abs(d) <= 0.25 : d <= reach;
                    if (in) {
                        out[(((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * samples.size()) + s] =
                            static_cast<std::int32_t>(k);
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
    std::size_t sliver = 0;  ///< pixels that differ near a stroked quad snapping made concave or crossed, or an n-gon's
                             ///< boundary
    std::size_t seam = 0;    ///< pixels that differ on the composite's seam (a resolving composite on lavapipe)
    std::size_t wrong = 0;   ///< pixels that differ otherwise
    std::size_t slivers = 0; ///< such quads in the frame
    std::size_t ngons = 0;   ///< n-gons in the frame
    std::string first;
};

[[nodiscard]] Compared compare(const platform::Pixels& got, const twin::Document& doc, const gpu::Configuration& config,
                               double density, std::uint32_t clear) {
    const std::vector<Pt> samples = sample_positions(config.samples());
    const std::uint32_t w = got.width;
    const std::uint32_t h = got.height;
    constexpr double tolerance = 0.02;
    const std::vector<Shape> shapes = shapes_of(doc, density);
    const std::vector<std::int32_t> exact = raster(shapes, w, h, samples, 0.0);
    const std::vector<std::int32_t> grown = raster(shapes, w, h, samples, tolerance);
    const std::vector<std::int32_t> shrunk = raster(shapes, w, h, samples, -tolerance);
    const std::vector<std::int32_t> near_sliver = raster(shapes, w, h, samples, 0.0, true);
    const std::size_t n = samples.size();
    const auto colour = [&](std::int32_t k) {
        return k == uncovered ? clear : shapes[static_cast<std::size_t>(k)].colour;
    };
    const auto resolved = [&](const std::vector<std::int32_t>& r, std::size_t i) {
        // the composite's mean of the samples' levels, rounded half to even (passes/Composite.hpp's composite_pixel)
        std::array<hlsl::float3, 4> cs{};
        for (std::size_t s = 0; s < n; ++s) {
            const std::uint32_t c = colour(r[(i * n) + s]);
            cs[s] = {static_cast<float>((c >> 16U) & 0xFFU) / 255.0F, static_cast<float>((c >> 8U) & 0xFFU) / 255.0F,
                     static_cast<float>(c & 0xFFU) / 255.0F};
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
    if (const char* out = std::getenv("ATLAS_BUILDINGS_PPM"); out != nullptr) { // NOLINT: a test's diagnosis
        platform::Pixels ref = got;
        for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
            const std::array<std::uint32_t, 3> want = resolved(exact, i);
            for (std::size_t k = 0; k < 3; ++k) ref.rgba[(i * 4) + k] = static_cast<std::uint8_t>(want[k]);
        }
        (void)platform::write_ppm(ref, std::format("{}/exact {}.ppm", out, gpu::name_of(config)));
    }
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool covered = false;
        for (std::size_t s = 0; s < n; ++s) covered = covered || exact[(i * n) + s] != uncovered;
        for (std::size_t s = 0; s < n; ++s) covered = covered || grown[(i * n) + s] != uncovered;
        if (!covered) continue;
        ++c.pixels;
        const std::array<std::uint32_t, 3> want = resolved(exact, i);
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        if (want == have) {
            ++c.agree;
            continue;
        }
        // a sample within the tolerance of a boundary ends with another shape (however alike their colours) when the
        // shapes grow or shrink by it
        bool ambiguous = false;
        bool sliver = false;
        for (std::size_t s = 0; s < n; ++s) {
            const std::size_t k = (i * n) + s;
            ambiguous = ambiguous || exact[k] != grown[k] || exact[k] != shrunk[k];
            sliver = sliver || near_sliver[k] != uncovered;
        }
        if (ambiguous || resolved(grown, i) == have || resolved(shrunk, i) == have) {
            ++c.boundary;
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
            for (std::size_t k = 0; k < shapes.size(); ++k) {
                double d = 1e300;
                for (std::size_t q = 0; q < n; ++q) {
                    const Pt p{static_cast<double>(i % w) + samples[q].x, static_cast<double>(i / w) + samples[q].y};
                    d = std::min(d, signed_distance(p, shapes[k].poly));
                }
                if (d > 0.5 + shapes[k].stroke) continue;
                covering += std::format("\n  shape {} colour {:06x} d {:.4f} stroke {:.4f} sliver {} points", k,
                                        shapes[k].colour, d, shapes[k].stroke, shapes[k].sliver);
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
/// (the CPU's light lights every drawn tile again) and that frame is held to the twin too.
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least, const style::ThemeBlock* then = nullptr) {
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
            const Compared r = compare(*pixels, decoded.document, config, c.density, clear);
            const std::string name = std::format("{} {} {}", view, gpu::name_of(config), rest ? "at rest" : "moving");
            MESSAGE(name << ": " << stream.elements() << " elements; " << r.pixels << " pixels of buildings, "
                         << r.agree << " the exact raster's, " << r.boundary
                         << " within 0.02 px of a shape's boundary, " << r.sliver << " near the " << r.slivers
                         << " edged quads snapping made concave or crossed or the " << r.ngons
                         << " n-gons' boundaries, " << r.seam << " on the composite's seam, " << r.wrong
                         << " otherwise; " << s.world->tiles_line() << "; the walls heap "
                         << static_cast<double>(heap.used[walls]) / (1 << 20) << " MB of the heaps' "
                         << static_cast<double>(heap.total_used()) / (1 << 20) << " MB; "
                         << frame::text_of(s.builder->total()));
            CHECK(stream.elements() >= least);
            // the sunny moment's sun bits reach the frame: some faces lit by them, as the GPU draws them
            const passes::Buildings::Emitted emitted = s.buildings->emitted();
            MESSAGE(name << ": " << emitted.sunlit << " of " << emitted.elements << " elements lit by their sun bit");
            CHECK(emitted.elements == stream.elements());
            if (s.lit) CHECK(emitted.sunlit > emitted.elements / 20);
            CHECK_MESSAGE(r.wrong == 0, name << ": " << r.first);
            CHECK(static_cast<double>(r.boundary) <= 0.01 * static_cast<double>(r.pixels));
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
                const Compared r2 = compare(*again, d2.document, config, c.density, clear);
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

/// Writes the synthetic tile of tests/building/fixture.hpp as a render pack (14/8414/5384, the tile reader's tests'
/// head: the Westerkerk's frame) to a file of its own; its path.
[[nodiscard]] std::string synthetic_pack() {
    test::TileBytes t = test::building::tile();
    const std::vector<std::byte> raw = t.encode();
    constexpr std::array<std::uint8_t, 1> levels = {14};
    const std::array<test::ChunkBytes, 1> chunks{test::ChunkBytes{
        .tile = {14, 8414, 5384}, .raw = raw, .count = test::building::tile_features, .reach = t.reach()}};
    const std::vector<std::byte> bytes = test::pack_bytes(chunks, levels);
    const std::filesystem::path path =
        std::filesystem::temp_directory_path() / std::format("atlas-buildings-test-{}.atlas", ::getpid());
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); // NOLINT
    return path.string();
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

#endif
