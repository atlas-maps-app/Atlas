// A region's items' pass on the host's Vulkan (ENGINE.md step 4, "Trees and things"; lavapipe): a window built from
// Overpass's JSON with a tower and its cab, a turbine, a crane on a quay and one in a yard, a lighting mast, a gantry,
// a goods conveyor and a power line, by day and at night, near and where the power line fades, and the canal ring's
// towers and masts (ATLAS_TILES_DIR), drawn through the frame builder and the world with that pass alone beside the
// composite, at rest and moving, at 1x and 4x, at true depth and under the painter switch, against an exact CPU raster
// of the records the twin says items_cs writes: each face's fan by its edges and its stroke's edges sample by sample
// (items.slangh's own tests, in float, at the samples' CSS points), each segment's and circle's samples; each sample's
// depth as the rasteriser interpolates the vertex stage's corners over the triangle it lies in (item_segment_corner's
// quads for the strokes and segments); each sample ending with the record of the greatest depth (the first drawn where
// two tie, as GREATER keeps it), a sample where two of different colours lie within the rasteriser's rounding of each
// other counted apart, and a sample within 0.02 px of a shape's boundary; then the translucent records (the conductors
// leaving the samples their path's earlier segments cover, a glow at its gradient, a light) blended over it in the
// GPU's order at each one's opacity where its depth passes (within a level a blend: lavapipe blends in fixed point);
// the samples averaged as the composite averages them. The twin's elements are held to the port's by L1; here its
// records are the GPU's.
#if !defined(__ANDROID__)

#include "base/arena.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "geometry/Items.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "load/Amsterdam.hpp"
#include "load/Window.hpp"
#include "passes/Composite.hpp"
#include "passes/Items.hpp"
#include "scene/World.hpp"
#include "style/Theme.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/ground/ground_support.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"
#include "twin/Twin.hpp"
#include "twin/item/ItemShader.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <filesystem>
#include <format>
#include <fstream>
#include <memory>
#include <span>
#include <string>
#include <vector>

using namespace atlas;

namespace {

namespace sh = atlas::shaders;

struct PackFile {
    std::string id;
    tiles::PackKind kind = tiles::PackKind::bundled;
    std::string path;
};

struct Scene {
    std::unique_ptr<gpu::Offscreen> output;
    std::unique_ptr<frame::FrameBuilder> builder;
    std::unique_ptr<scene::World> world;
    std::unique_ptr<gpu::Renderer> renderer;
    passes::Items* items = nullptr;
    double now = 0;
    bool settled = false;
};

[[nodiscard]] std::optional<Scene> make_scene(const test::Session& session, const gpu::PipelineCache& cache,
                                              const gpu::Configuration& config, std::uint32_t w, std::uint32_t h,
                                              std::span<const PackFile> packs, std::uint32_t clear) {
    const gpu::Device& device = *session.device;
    Scene s;
    auto output = gpu::Offscreen::create(device, {.width = w, .height = h}, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    s.output = std::move(*output);
    // the registry's composite and the items' pass of its own (the test reads its twin)
    constexpr std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto items = std::make_unique<passes::Items>();
    s.items = items.get();
    passes.push_back(std::move(items));
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
        auto added = s.world->store().add({.id = pack.id, .kind = pack.kind, .path = pack.path});
        ATLAS_REQUIRE_OR_RETURN(std::nullopt, added.has_value(), tiles::describe(added.error()));
    }
    auto renderer = gpu::Renderer::create(device, *s.output, *s.builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    s.renderer = std::move(*renderer);
    return s;
}

/// Draws `cam` until the world has built every tile the cover wants and their fades are over; the last frame's pixels.
[[nodiscard]] std::optional<gpu::Pixels> draw(Scene& s, const style::CameraState& cam, const style::ThemeBlock& th) {
    for (int n = 0; n < 61; ++n) {
        s.now += 0.25;
        s.builder->set_inputs(
            {.camera = cam, .theme = th, .seconds = s.now, .mode = frame::Mode::structural, .scene = s.world.get()});
        auto report = s.renderer->draw(0);
        if (!report) {
            FAIL("a frame failed: " << report.error().text());
            return std::nullopt;
        }
        s.renderer->drain();
        if (s.settled) break; // this frame drawn with every tile taken
        s.world->residency().wait_idle();
        const scene::Cover& cover = *s.world->cover();
        s.settled = n > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                    std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    auto pixels = s.output->read(s.output->latest());
    if (!pixels) {
        FAIL("the frame could not be read: " << pixels.error().text());
        return std::nullopt;
    }
    return std::move(*pixels);
}

[[nodiscard]] std::vector<hlsl::float2> sample_positions(vk::SampleCountFlagBits samples) {
    switch (samples) {
        using F = hlsl::float2;
    case vk::SampleCountFlagBits::e4:
        return {F{0.375F, 0.125F}, F{0.875F, 0.375F}, F{0.125F, 0.625F}, F{0.625F, 0.875F}};
    case vk::SampleCountFlagBits::e2: return {F{0.75F, 0.75F}, F{0.25F, 0.25F}};
    default: return {F{0.5F, 0.5F}};
    }
}

/// Whether pixel (x, y) of a w × h frame lies on the composite's seam (buildings_vk_test.cpp's on_seam).
[[nodiscard]] bool on_seam(std::size_t x, std::size_t y, std::size_t w, std::size_t h) noexcept {
    const std::uint64_t above = std::uint64_t{y} * w;
    const std::uint64_t below = above + w;
    const std::uint64_t left = std::uint64_t{x} * h;
    const std::uint64_t right = left + h;
    return above < right && below > left;
}

/// A triangle the vertex stage draws: its corners (CSS px) and their depths.
struct Tri {
    std::array<std::array<double, 3>, 3> v;
};

/// Where a sample lies against a triangle: its barycentric weights' least (inside where 0 or more), its depth there
/// interpolated as the rasteriser does, and its distance from the triangle's nearest edge.
struct InTri {
    double least = -1e300;
    double depth = 0;
    double edge = 1e300;
};

[[nodiscard]] InTri in_tri(const Tri& t, double x, double y) {
    InTri r;
    const auto& a = t.v[0];
    const auto& b = t.v[1];
    const auto& c = t.v[2];
    const double det = ((b[0] - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (b[1] - a[1]));
    if (std::abs(det) < 1e-12) return r;
    const double l1 = (((x - a[0]) * (c[1] - a[1])) - ((c[0] - a[0]) * (y - a[1]))) / det;
    const double l2 = (((b[0] - a[0]) * (y - a[1])) - ((x - a[0]) * (b[1] - a[1]))) / det;
    const double l0 = 1.0 - l1 - l2;
    r.least = std::min({l0, l1, l2});
    r.depth = (l0 * a[2]) + (l1 * b[2]) + (l2 * c[2]);
    for (std::size_t i = 0; i < 3; ++i) {
        const auto& p = t.v[i];
        const auto& q = t.v[(i + 1) % 3];
        const double ex = q[0] - p[0];
        const double ey = q[1] - p[1];
        const double len = std::sqrt((ex * ex) + (ey * ey));
        if (len > 0) r.edge = std::min(r.edge, std::abs((ex * (y - p[1])) - (ey * (x - p[0]))) / len);
    }
    return r;
}

/// The quad's two triangles (corners 0 1 2, 0 2 3) item_segment_corner gives about a segment.
[[nodiscard]] std::array<Tri, 2> segment_tris(hlsl::float2 a, hlsl::float2 b, float d0, float d1, float e) {
    std::array<std::array<double, 3>, 4> k{};
    for (std::uint32_t i = 0; i < 4; ++i) {
        const sh::ItemCorner c = sh::item_segment_corner(a, b, d0, d1, e, i);
        k[i] = {static_cast<double>(c.p.x), static_cast<double>(c.p.y), static_cast<double>(c.depth)};
    }
    return {Tri{{k[0], k[1], k[2]}}, Tri{{k[0], k[2], k[3]}}};
}

/// What a frame's comparison with the exact raster found.
struct Compared {
    std::size_t pixels = 0;   ///< pixels some record covers in the reference
    std::size_t agree = 0;    ///< pixels equal to the reference
    std::size_t boundary = 0; ///< pixels that differ, a sample within 0.02 px of a shape's boundary
    std::size_t tied = 0;     ///< pixels that differ, two colours tied in depth at a sample
    std::size_t seam = 0;     ///< pixels that differ on the composite's seam
    std::size_t wrong = 0;
    std::string first;
};

/// A sample's coverage by one record: whether it covers it, whether it lies within the tolerance of the shape's
/// boundary, and its depth there.
struct Hit {
    bool inside = false;
    bool near = false;
    double depth = 0;
};

/// The GPU's frame `got` against the exact raster of the records (faces, then the opaque parts, then the translucent
/// ones, in the GPU's order) with the moment's `tones`.
[[nodiscard]] Compared compare(const gpu::Pixels& got, std::span<const sh::ItemRecord> faces,
                               std::span<const sh::ItemRecord> parts, std::span<const sh::ItemRecord> sides,
                               std::span<const std::uint32_t> tones, const sh::FrameBlock& block,
                               const gpu::Configuration& config, std::uint32_t clear) {
    const sh::FrameCamera& cam = block.camera;
    const bool painter = (cam.flags.x & sh::CAMERA_PAINTER) != 0U;
    const std::vector<hlsl::float2> samples = sample_positions(config.samples());
    const std::uint32_t w = got.width;
    const std::uint32_t h = got.height;
    const std::size_t n = samples.size();
    const auto density = static_cast<double>(cam.frame.z);
    const double tol = 0.02 / density;
    constexpr double slack = 3.814697265625e-6; // 2^-18: the rasteriser's rounding of a depth against the raster's
    std::vector<hlsl::float3> colour(std::size_t{w} * h * n);
    std::vector<std::uint8_t> covered(colour.size(), 0);
    std::vector<std::uint8_t> edge(colour.size(), 0);
    std::vector<std::uint8_t> tie(colour.size(), 0);
    std::vector<std::uint8_t> blended(colour.size(), 0);
    std::vector<double> depth(colour.size(), 0.0);
    std::vector<std::uint32_t> owner(colour.size(), 0xFFFF'FFFFU); // the colour the nearest opaque record gave
    const hlsl::float3 background{static_cast<float>((clear >> 16U) & 0xFFU) / 255.0F,
                                  static_cast<float>((clear >> 8U) & 0xFFU) / 255.0F,
                                  static_cast<float>(clear & 0xFFU) / 255.0F};
    std::ranges::fill(colour, background);
    const auto f = [](std::uint32_t v) { return std::bit_cast<float>(v); };
    // the samples of a box of CSS px, each its index, device point and CSS point
    const auto each = [&](double x0, double y0, double x1, double y1, auto&& at) {
        const auto px0 = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(x0 * density)) - 1);
        const auto py0 = std::max<std::int64_t>(0, static_cast<std::int64_t>(std::floor(y0 * density)) - 1);
        const auto px1 = std::min<std::int64_t>(w - 1, static_cast<std::int64_t>(std::ceil(x1 * density)) + 1);
        const auto py1 = std::min<std::int64_t>(h - 1, static_cast<std::int64_t>(std::ceil(y1 * density)) + 1);
        for (std::int64_t y = py0; y <= py1; ++y) {
            for (std::int64_t x = px0; x <= px1; ++x) {
                for (std::size_t q = 0; q < n; ++q) {
                    const hlsl::float2 dev{static_cast<float>(x) + samples[q].x, static_cast<float>(y) + samples[q].y};
                    const hlsl::float2 s = sh::css_of_output(cam, dev);
                    at((((static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)) * n) + q, s,
                       hlsl::float2{static_cast<float>(x) + 0.5F, static_cast<float>(y) + 0.5F});
                }
            }
        }
    };
    // a hit by the triangles `tris` covering through `covers` (the shape's own test), its depth the triangles'
    const auto tri_hit = [&](std::span<const Tri> tris, hlsl::float2 s, bool covers, double boundary) {
        Hit hit{.inside = covers, .near = boundary <= tol};
        double best = -1e300;
        for (const Tri& t : tris) {
            const InTri in = in_tri(t, static_cast<double>(s.x), static_cast<double>(s.y));
            if (in.least > best) {
                best = in.least;
                hit.depth = in.depth;
            }
        }
        return hit;
    };
    // an opaque sample: the nearest wins (GREATER: the first of two tied); two colours within the slack are tied
    const auto opaque = [&](std::size_t k, const Hit& hit, std::uint32_t c, hlsl::float3 rgb) {
        if (hit.near) edge[k] = 1;
        if (!hit.inside) return;
        const double d = hit.depth;
        if (covered[k] != 0 && owner[k] != c && !painter && std::abs(d - depth[k]) <= std::max(d, depth[k]) * slack) {
            tie[k] = 1;
        }
        if (covered[k] != 0 && !(d > depth[k])) return;
        depth[k] = d;
        colour[k] = rgb;
        owner[k] = c;
        covered[k] = 1;
    };
    // the faces: the fan by its edges, each edge's stroke sample by sample
    for (const sh::ItemRecord& r : faces) {
        if ((r.a.x & sh::ITEM_RECORD_KIND) != sh::ITEM_FACE) continue;
        const std::uint32_t count = sh::item_count(r);
        std::vector<Tri> fan;
        double x0 = 1e300;
        double y0 = 1e300;
        double x1 = -1e300;
        double y1 = -1e300;
        float gx0 = sh::item_point(r, 0).x;
        float gx1 = gx0;
        for (std::uint32_t i = 0; i < count; ++i) {
            const hlsl::float2 p = sh::item_point(r, i);
            x0 = std::min(x0, static_cast<double>(p.x));
            y0 = std::min(y0, static_cast<double>(p.y));
            x1 = std::max(x1, static_cast<double>(p.x));
            y1 = std::max(y1, static_cast<double>(p.y));
            gx0 = std::min(gx0, p.x);
            gx1 = std::max(gx1, p.x);
        }
        for (std::uint32_t t = 0; t + 2 < count; ++t) {
            std::array<std::array<double, 3>, 3> v{};
            const std::array<std::uint32_t, 3> at{0U, t + 1, t + 2};
            for (std::size_t i = 0; i < 3; ++i) {
                const hlsl::float2 p = sh::item_point(r, at[i]);
                v[i] = {static_cast<double>(p.x), static_cast<double>(p.y),
                        static_cast<double>(sh::item_corner_depth(r, at[i]))};
            }
            fan.push_back(Tri{v});
        }
        const bool stroked = (r.a.x & sh::ITEM_STROKED) != 0U;
        const float half = f(r.a.w);
        const double grow = stroked ? static_cast<double>(half) + 1.0 : 0.0;
        const bool gradient = (r.a.x & sh::ITEM_GRADIENT) != 0U;
        each(x0 - grow, y0 - grow, x1 + grow, y1 + grow, [&](std::size_t k, hlsl::float2 s, hlsl::float2 centre) {
            hlsl::float3 rgb = sh::tree_rgb(r.a.y);
            if (gradient) {
                const hlsl::float2 c = sh::css_of_output(cam, centre);
                rgb = sh::item_gradient_at(sh::tree_rgb(r.a.y), sh::tree_rgb(r.a.w), sh::tree_rgb(r.e.w),
                                           (r.a.x & sh::ITEM_HIGH) != 0U, gx0, gx1, c.x);
            }
            const std::uint32_t key = gradient ? 0x1000'0000U ^ r.a.y : r.a.y;
            // the fan: inside a triangle by its edges
            bool inside = false;
            double boundary = 1e300;
            for (const Tri& t : fan) {
                const InTri in = in_tri(t, static_cast<double>(s.x), static_cast<double>(s.y));
                inside = inside || in.least >= 0;
                boundary = std::min(boundary, in.edge);
            }
            if (inside || boundary <= tol) opaque(k, tri_hit(fan, s, inside, boundary), key, rgb);
            if (!stroked) return;
            for (std::uint32_t e = 0; e < count; ++e) {
                const hlsl::float2 a = sh::item_point(r, e);
                const hlsl::float2 b = sh::item_point(r, (e + 1) % count);
                const bool covers = sh::tree_segment_covers(s, a, b, half, sh::ITEM_ROUND);
                const double ex = static_cast<double>(b.x) - static_cast<double>(a.x);
                const double ey = static_cast<double>(b.y) - static_cast<double>(a.y);
                const double dx = static_cast<double>(s.x) - static_cast<double>(a.x);
                const double dy = static_cast<double>(s.y) - static_cast<double>(a.y);
                const double ee = (ex * ex) + (ey * ey);
                const double t = ee > 0 ? std::clamp(((dx * ex) + (dy * ey)) / ee, 0.0, 1.0) : 0.0;
                const double dist = pymath::hypot(dx - (ex * t), dy - (ey * t));
                if (!covers && std::abs(dist - static_cast<double>(half)) > tol) continue;
                const std::array<Tri, 2> quad = segment_tris(
                    a, b, sh::item_corner_depth(r, e), sh::item_corner_depth(r, (e + 1) % count), half + cam.device.z);
                opaque(k, tri_hit(quad, s, covers, std::abs(dist - static_cast<double>(half))), key, rgb);
            }
        });
    }
    // a segment's or a circle's samples, and its depth there
    const auto part_hit = [&](const sh::ItemRecord& r, hlsl::float2 s) {
        const std::uint32_t kind = r.a.x & sh::ITEM_RECORD_KIND;
        const hlsl::float2 a = sh::item_point(r, 0);
        const float radius = f(r.a.w);
        if (kind == sh::ITEM_SEGMENT) {
            const hlsl::float2 b = sh::item_point(r, 1);
            const bool covers = sh::tree_segment_covers(s, a, b, radius, r.a.x);
            const double ex = static_cast<double>(b.x) - static_cast<double>(a.x);
            const double ey = static_cast<double>(b.y) - static_cast<double>(a.y);
            const double dx = static_cast<double>(s.x) - static_cast<double>(a.x);
            const double dy = static_cast<double>(s.y) - static_cast<double>(a.y);
            const double ee = (ex * ex) + (ey * ey);
            const double along = ee > 0 ? ((dx * ex) + (dy * ey)) / ee : 0.0;
            const double t = std::clamp(along, 0.0, 1.0);
            double boundary = std::abs(pymath::hypot(dx - (ex * t), dy - (ey * t)) - static_cast<double>(radius));
            const double len = std::sqrt(ee);
            if (len > 0 && (r.a.x & sh::ITEM_ROUND_START) == 0U) boundary = std::min(boundary, std::abs(along) * len);
            if (len > 0 && (r.a.x & sh::ITEM_ROUND_END) == 0U)
                boundary = std::min(boundary, std::abs(along - 1.0) * len);
            const std::array<Tri, 2> quad = segment_tris(a, b, f(r.d.z), f(r.d.w), radius + cam.device.z);
            return tri_hit(quad, s, covers, boundary);
        }
        const bool covers = sh::tree_circle_covers(s, a, radius);
        const double d = pymath::hypot(static_cast<double>(s.x) - static_cast<double>(a.x),
                                       static_cast<double>(s.y) - static_cast<double>(a.y));
        return Hit{.inside = covers,
                   .near = std::abs(d - static_cast<double>(radius)) <= tol,
                   .depth = static_cast<double>(f(r.d.z))};
    };
    const auto part_box = [&](const sh::ItemRecord& r) {
        const hlsl::float2 a = sh::item_point(r, 0);
        const hlsl::float2 b = (r.a.x & sh::ITEM_RECORD_KIND) == sh::ITEM_SEGMENT ? sh::item_point(r, 1) : a;
        const double e = static_cast<double>(f(r.a.w)) + 1.0;
        const auto d = [](float v) { return static_cast<double>(v); };
        return std::array<double, 4>{std::min(d(a.x), d(b.x)) - e, std::min(d(a.y), d(b.y)) - e,
                                     std::max(d(a.x), d(b.x)) + e, std::max(d(a.y), d(b.y)) + e};
    };
    for (const sh::ItemRecord& r : parts) {
        if ((r.a.x & sh::ITEM_RECORD_KIND) == sh::ITEM_NONE) continue;
        const std::array<double, 4> b = part_box(r);
        each(b[0], b[1], b[2], b[3], [&](std::size_t k, hlsl::float2 s, hlsl::float2) {
            opaque(k, part_hit(r, s), r.a.y, sh::tree_rgb(r.a.y));
        });
    }
    // the translucent ones in the GPU's order: a conveyor's segment leaves what its path's earlier segments cover
    for (std::size_t i = 0; i < sides.size(); ++i) {
        const sh::ItemRecord& r = sides[i];
        const std::uint32_t kind = r.a.x & sh::ITEM_RECORD_KIND;
        if (kind == sh::ITEM_NONE) continue;
        const std::uint32_t back = r.a.x >> sh::ITEM_BACK_SHIFT;
        const std::array<double, 4> b = part_box(r);
        each(b[0], b[1], b[2], b[3], [&](std::size_t k, hlsl::float2 s, hlsl::float2 centre) {
            const Hit hit = part_hit(r, s);
            if (hit.near) edge[k] = 1;
            if (!hit.inside) return;
            for (std::uint32_t j = 1; j <= back && j <= i; ++j) {
                if (part_hit(sides[i - j], s).inside) return;
            }
            if (covered[k] != 0 && !(hit.depth > depth[k])) return;
            hlsl::float3 c = sh::tree_rgb(r.a.y);
            float alpha = f(r.a.z);
            if (kind == sh::ITEM_GLOW) {
                const hlsl::float2 at = sh::css_of_output(cam, centre);
                const hlsl::float2 a = sh::item_point(r, 0);
                const float dx = at.x - a.x;
                const float dy = at.y - a.y;
                const hlsl::float4 g = sh::item_glow_at(
                    sh::tree_rgb(tones[sh::ITEM_TONE_GLOW]), sh::tree_rgb(tones[sh::ITEM_TONE_GLOW + 1]),
                    sh::tree_rgb(tones[sh::ITEM_TONE_GLOW + 2]), f(tones[sh::ITEM_TONE_GLOW_OPACITY]),
                    f(tones[sh::ITEM_TONE_GLOW_OPACITY + 1]), f(tones[sh::ITEM_TONE_GLOW_OPACITY + 2]),
                    std::sqrt((dx * dx) + (dy * dy)) / f(r.a.w));
                c = {g.x, g.y, g.z};
                alpha = g.w;
            }
            const hlsl::float3 under = colour[k];
            const hlsl::float3 over = (c * alpha) + (under * (1.0F - alpha));
            colour[k] = {std::round(over.x * 255.0F) / 255.0F, std::round(over.y * 255.0F) / 255.0F,
                         std::round(over.z * 255.0F) / 255.0F};
            ++blended[k];
            if (covered[k] == 0) covered[k] = 2;
        });
    }
    Compared c;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        bool any = false;
        bool near_edge = false;
        bool tied = false;
        std::uint32_t blends = 0;
        for (std::size_t q = 0; q < n; ++q) {
            any = any || covered[(i * n) + q] != 0 || edge[(i * n) + q] != 0;
            near_edge = near_edge || edge[(i * n) + q] != 0;
            tied = tied || tie[(i * n) + q] != 0;
            blends = std::max<std::uint32_t>(blends, blended[(i * n) + q]);
        }
        if (!any) continue;
        ++c.pixels;
        shaders::FrameLayers stubbed{};
        stubbed.steps = {0U, 255U, 0U, 0U};
        const hlsl::float3 p =
            passes::composite_pixel(stubbed, std::span(colour.data() + (i * n), n), {0.5F, 0.5F}, {1.0F, 1.0F});
        const std::array<std::uint32_t, 3> want{static_cast<std::uint32_t>(p.x), static_cast<std::uint32_t>(p.y),
                                                static_cast<std::uint32_t>(p.z)};
        const std::array<std::uint32_t, 3> have{got.rgba[i * 4], got.rgba[(i * 4) + 1], got.rgba[(i * 4) + 2]};
        // a level a blend, and one for a gradient's float against the raster's
        const std::uint32_t levels = blends + 1U;
        bool same = true;
        for (std::size_t k = 0; k < 3; ++k) {
            if ((want[k] > have[k] ? want[k] - have[k] : have[k] - want[k]) > levels) same = false;
        }
        if (same) {
            ++c.agree;
        } else if (near_edge) {
            ++c.boundary;
        } else if (tied) {
            ++c.tied;
        } else if (config.target == gpu::CompositeTarget::resolve && on_seam(i % w, i / w, w, h)) {
            ++c.seam;
        } else {
            if (c.wrong == 0) {
                c.first =
                    std::format("pixel ({}, {}): {:02x}{:02x}{:02x} where the exact raster has {:02x}{:02x}{:02x}",
                                i % w, i / w, have[0], have[1], have[2], want[0], want[1], want[2]);
            }
            ++c.wrong;
        }
    }
    return c;
}

/// Draws `cam` at 1x and 4x, at rest and moving, at true depth and under the painter switch, at moment `th`, and
/// holds every pixel to the exact raster of the twin's records; the twin emits at least `least` elements.
void check_frames(const style::CameraState& cam, const std::string& view, std::span<const PackFile> packs,
                  const style::ThemeBlock& th, std::size_t least) {
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
        for (const bool painter : {false, true}) {
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
                twin::Stream stream;
                std::vector<sh::ItemRecord> faces;
                std::vector<sh::ItemRecord> parts;
                std::vector<sh::ItemRecord> sides;
                const frame::FrameState& state = s.builder->state();
                s.items->emit_records(state, stream, &faces, &parts, &sides);
                const twin::Decoded decoded = twin::decode(stream.bytes());
                ATLAS_REQUIRE(decoded.error.empty(), decoded.error);
                const passes::Items::Counts counts = s.items->counts();
                ATLAS_REQUIRE(state.active_pack != nullptr && state.active_pack->source);
                const tiles::Style* style = state.active_pack->source->pack.style();
                ATLAS_REQUIRE(style != nullptr);
                const passes::ItemTones tones = passes::item_tones(state.theme, state.block.theme, style->items);
                const Compared r = compare(*pixels, faces, parts, sides, tones, state.block, config, clear);
                const std::string name = std::format("{} {} {}{}", view, gpu::name_of(config),
                                                     rest ? "at rest" : "moving", painter ? " (painter's order)" : "");
                MESSAGE(name << ": " << stream.elements() << " elements of " << counts.jobs << " items, "
                             << counts.faces << " faces, " << counts.parts << " parts and " << counts.sides
                             << " translucent ones; " << r.pixels << " pixels of items, " << r.agree
                             << " the exact raster's, " << r.boundary << " within 0.02 px of a shape's boundary, "
                             << r.tied << " where two colours tie in depth, " << r.seam << " on the composite's seam, "
                             << r.wrong << " otherwise; " << frame::text_of(s.builder->total()));
                CHECK(stream.elements() >= least);
                CHECK(faces.size() == counts.faces);
                CHECK(parts.size() == counts.parts);
                CHECK(sides.size() == counts.sides);
                CHECK_MESSAGE(r.wrong == 0, name << ": " << r.first);
                CHECK(static_cast<double>(r.boundary + r.tied) <= 0.01 * static_cast<double>(r.pixels));
                for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, name << ": " << m);
                s.renderer.reset();
            }
        }
    }
}

/// A window about the Westerkerk with a region's items: a tower and its cab, a turbine, a crane on a quay (a bay's
/// edge: a window's water has natural shores) and one in a yard (its outline), a lighting mast, a gantry over a road, a
/// goods conveyor and a power line; its pack written to the tests' scratch folder.
[[nodiscard]] std::string items_window() {
    const tiles::TileKey k = tiles::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "node", "id": 101, "lat": 52.3752, "lon": 4.8846,
    "tags": {"man_made": "tower", "tower:type": "observation", "height": "45"}},
{"type": "node", "id": 102, "lat": 52.3749, "lon": 4.8828,
    "tags": {"power": "generator", "generator:source": "wind", "height": "60", "rotor:diameter": "50"}},
{"type": "node", "id": 103, "lat": 52.3741, "lon": 4.8852, "tags": {"man_made": "mast", "tower:type": "lighting"}},
{"type": "way", "id": 201, "tags": {"man_made": "gantry"},
    "geometry": [{"lat": 52.3738, "lon": 4.8832}, {"lat": 52.3738, "lon": 4.8838}]},
{"type": "way", "id": 202, "tags": {"man_made": "goods_conveyor"},
    "geometry": [{"lat": 52.3735, "lon": 4.8826}, {"lat": 52.3744, "lon": 4.8860}]},
{"type": "way", "id": 203, "tags": {"power": "line"},
    "geometry": [{"lat": 52.3731, "lon": 4.8815}, {"lat": 52.3733, "lon": 4.8850}, {"lat": 52.3736, "lon": 4.8885}]},
{"type": "way", "id": 204, "tags": {"natural": "bay"},
    "geometry": [{"lat": 52.37555, "lon": 4.8832}, {"lat": 52.37555, "lon": 4.8852}, {"lat": 52.3762, "lon": 4.8852},
                 {"lat": 52.3762, "lon": 4.8832}, {"lat": 52.37555, "lon": 4.8832}]},
{"type": "node", "id": 104, "lat": 52.3754, "lon": 4.8840, "tags": {"man_made": "crane"}},
{"type": "way", "id": 205, "tags": {"man_made": "crane"},
    "geometry": [{"lat": 52.3740, "lon": 4.8843}, {"lat": 52.3740, "lon": 4.88459}, {"lat": 52.37411, "lon": 4.88459},
                 {"lat": 52.37411, "lon": 4.8843}, {"lat": 52.3740, "lon": 4.8843}]}
]})";
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, load::place_of(k));
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "items-window";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "window.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A window about the Westerkerk as a theme park's (a test's: its rides' profiles and its woods): a steel coaster's
/// loop (a lift hill, a run at layer 2 and a brake run meeting it), a wooden coaster's run, a monorail, and a wood of
/// about a hundred trees; its pack written to the tests' scratch folder.
[[nodiscard]] std::string park_window() {
    const tiles::TileKey k = tiles::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "node", "id": 1, "lat": 52.37455, "lon": 4.88385, "tags": {"attraction": "roller_coaster", "name": "Big One"}},
{"type": "node", "id": 2, "lat": 52.37405, "lon": 4.88445, "tags": {"attraction": "roller_coaster", "name": "Woodie"}},
{"type": "way", "id": 10, "tags": {"roller_coaster": "track", "roller_coaster:track": "lift_hill"}, "geometry": [
  {"lat": 52.3744, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8841}, {"lat": 52.3747, "lon": 4.8841}]},
{"type": "way", "id": 11, "tags": {"roller_coaster": "track", "layer": "2"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8841}, {"lat": 52.37472, "lon": 4.88385}, {"lat": 52.3747, "lon": 4.8836}]},
{"type": "way", "id": 12, "tags": {"roller_coaster": "track", "roller_coaster:track": "brake_run"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8836}]},
{"type": "way", "id": 13, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [
  {"lat": 52.3740, "lon": 4.8842}, {"lat": 52.3741, "lon": 4.8847}, {"lat": 52.3740, "lon": 4.8852}]},
{"type": "way", "id": 14, "tags": {"railway": "monorail"}, "geometry": [
  {"lat": 52.3738, "lon": 4.8830}, {"lat": 52.3739, "lon": 4.8838}]},
{"type": "way", "id": 20, "tags": {"landuse": "forest"}, "geometry": [
  {"lat": 52.3748, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8852}, {"lat": 52.3753, "lon": 4.8852},
  {"lat": 52.3753, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8843}]}
]})";
    static const load::RideProfiles rides = [] {
        load::RideProfiles r;
        r.heights = {{"Big One", 30.0}, {"Woodie", 20.0}};
        r.wooden = {{"Woodie", true}};
        return r;
    }();
    load::WindowPlace place = load::place_of(k);
    place.park = &rides;
    // the woods about the window's tile, out to 1.2 km
    const load::V2 centre = load::xz(place.frame, 52.3745, 4.8842);
    place.woods = load::WoodsAnchor{.cx = centre.x, .cz = centre.z, .reach = 1200.0};
    const auto built = load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, place);
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / "items-park";
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "park.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

/// A camera of the map at S1/2 (540 × 1200 px, 412 × 916 dp), looking at (lat, lon).
[[nodiscard]] style::CameraState camera(double lat, double lon, double bearing, double pitch, double distance) {
    style::CameraState c = test::s1_camera(lat, lon, bearing, pitch, distance);
    c.width_px = 540;
    c.height_px = 1200;
    c.density = 540.0 / 412.0;
    c.flags = style::camera_flag::engine_mode;
    return c;
}

} // namespace

TEST_CASE("items: a window's tower, turbine, cranes, mast, gantry, conveyor and power line on the host's Vulkan, "
          "pixel for pixel the exact shapes of their twin") {
    const std::string path = items_window();
    ATLAS_REQUIRE(!path.empty());
    const std::array<PackFile, 1> packs{
        PackFile{.id = "window/" + load::place_of(tiles::tile_of(14, 523'746'000, 48'840'000)).name,
                 .kind = tiles::PackKind::window,
                 .path = path}};
    // close by day: every kind, the turbine's blades turned to 225°'s wind
    check_frames(camera(52.3744, 4.8840, 30.0, 55.0, 420.0), "the window's items by day", packs, test::sunny(), 30);
    // at night: the lighting mast's glow and its core, the turbine's light
    check_frames(camera(52.3744, 4.8840, 200.0, 50.0, 380.0), "the window's items at night", packs, test::night(), 30);
    // far: the power line fading (1,200 to 2,000 m), its tower translucent
    check_frames(camera(52.3744, 4.8840, 120.0, 30.0, 1600.0), "the window's items far off", packs, test::sunny(), 3);
}

TEST_CASE("items: a park's coaster tracks and woods' trees on the host's Vulkan, pixel for pixel the exact shapes of "
          "their twin") {
    const std::string path = park_window();
    ATLAS_REQUIRE(!path.empty());
    const std::array<PackFile, 1> packs{
        PackFile{.id = "window/" + load::place_of(tiles::tile_of(14, 523'746'000, 48'840'000)).name,
                 .kind = tiles::PackKind::window,
                 .path = path}};
    // close by day: the tracks' posts and braces, the woods' clumps with their lit tops and glints
    check_frames(camera(52.3745, 4.8842, 30.0, 50.0, 260.0), "the park by day", packs, test::sunny(), 30);
    // at night: no lit tops
    check_frames(camera(52.3745, 4.8842, 200.0, 45.0, 240.0), "the park at night", packs, test::night(), 30);
}

TEST_CASE("items: the canal ring's towers and masts on the host's Vulkan, pixel for pixel the exact shapes of their "
          "twin") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the canal ring is not drawn here");
        return;
    }
    // the canal ring's tallest towers, looked at from 300 m
    auto pack = test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(pack.has_value(), tiles::describe(pack.error()));
    const tiles::RenderHead* head = pack->head();
    ATLAS_REQUIRE(head != nullptr);
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "items vk test");
    double best = 0;
    double x = 0;
    double z = 0;
    for (const tiles::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
        arena.reset();
        auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
        ATLAS_REQUIRE(tile.has_value(), tiles::describe(tile.error()));
        for (const geometry::RegionItem& it : geometry::build_region_items({.tile = &*tile, .head = head}).items) {
            if (it.kind == geometry::RegionItemKind::tower && it.h > best) {
                best = it.h;
                x = it.x;
                z = it.z;
            }
        }
    }
    ATLAS_REQUIRE(best > 0);
    const tiles::Frame& f = head->frame;
    const double lat = f.lat0 - (z / f.kz);
    const double lon = f.lon0 + (x / f.kx);
    MESSAGE("the canal ring's tallest tower: " << best << " m");
    const std::array<PackFile, 1> packs{PackFile{.id = "grachten", .path = dir + "/grachten.atlas"}};
    check_frames(camera(lat, lon, 52.0, 50.0, 300.0), "the canal ring's tallest tower", packs, test::sunny(), 1);
}

#endif
