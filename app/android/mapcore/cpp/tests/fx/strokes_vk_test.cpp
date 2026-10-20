// The objects group's thin strokes on the host's Vulkan (passes/ObjectStrokes.hpp, shaders/fx/strokes.slang; lavapipe
// under the validation layer), drawn by a test pass through the frame builder, the renderer and an offscreen output,
// with the composite's steps stubbed so the frame is the scene's samples:
//   - the coverage: white outlines over black in structural mode, every pixel against the CPU's model of the same
//     butt segments blended in their order (the coverage by the shader's own steps in float, held within 2^-12 to the
//     exact area in double), within the blend's rounding: one level a blend;
//   - the residual of this first version, counted and named, never absorbed: against the true stroke (round joins:
//     the union of each outline's rectangles and its join disks, by 64 × 64 samples a pixel) the pixels that differ by
//     more than 2 levels, every one of them at a corner;
//   - the variants and the tests: outlines over the ground and over two objects (pixel-aligned rectangles writing the
//     objects bit), one behind an object (hidden there), one at the object's own slot (drawn over it), in structural
//     mode (alpha a, the stroke's colour, through both pipelines) and in full mode with the fields bound, at a moment
//     with weather (over the ground alpha min(1, 1.1·a)·a in mat's edge colour; over the objects alpha a in mat's
//     colour from mat_table).
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Mat.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frame/test_shapes.hpp"
#include "gen/spirv/fx/strokes.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/ObjectStrokes.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <format>
#include <memory>
#include <span>
#include <string>
#include <utility>
#include <vector>

#if !defined(__ANDROID__)
namespace {

using namespace atlas;

constexpr std::uint32_t frame_width = 200;
constexpr std::uint32_t frame_height = 150;
constexpr double density = 2.625;

/// An sRGB colour's bytes.
struct Rgb {
    std::uint8_t r = 0;
    std::uint8_t g = 0;
    std::uint8_t b = 0;
};

/// An opaque rectangle on pixel edges, device px [x0, x1) × [y0, y1): an object of the group (writing the objects
/// bit) or the ground, at a painter slot.
struct Box {
    std::uint32_t x0 = 0;
    std::uint32_t y0 = 0;
    std::uint32_t x1 = 0;
    std::uint32_t y1 = 0;
    Rgb colour;
    std::uint32_t slot = 0;
    bool objects = false;
};

/// A stroked outline: its points (CSS px), closed or open (butt ends), its width (CSS px), colour and slot.
struct Outline {
    std::vector<std::array<double, 2>> points;
    bool closed = true;
    double width = 0.25;
    Rgb colour;
    std::uint32_t slot = 0;
};

/// One segment as the frame draws it: its ends in device px, half its width (device px), its outline's colour, slot.
struct Seg {
    double ax = 0;
    double ay = 0;
    double bx = 0;
    double by = 0;
    double hw = 0;
    Rgb colour;
    std::uint32_t slot = 0;
    std::size_t outline = 0;
};

struct Scene {
    std::vector<Box> boxes;
    std::vector<Outline> outlines;
};

[[nodiscard]] std::vector<Seg> segments_of(const Scene& s) {
    std::vector<Seg> out;
    for (std::size_t o = 0; o < s.outlines.size(); ++o) {
        const Outline& l = s.outlines[o];
        const std::size_t n = l.points.size();
        const std::size_t edges = l.closed ? n : n - 1;
        for (std::size_t i = 0; i < edges; ++i) {
            const auto& a = l.points[i];
            const auto& b = l.points[(i + 1) % n];
            out.push_back({.ax = a[0] * density,
                           .ay = a[1] * density,
                           .bx = b[0] * density,
                           .by = b[1] * density,
                           .hw = 0.5 * l.width * density,
                           .colour = l.colour,
                           .slot = l.slot,
                           .outline = o});
        }
    }
    return out;
}

/// The exact area of a segment's rectangle (butt ends) in pixel (x, y), in double: the rectangle clipped to the
/// pixel's square (Sutherland and Hodgman), by the shoelace.
[[nodiscard]] double exact_area(const Seg& s, std::uint32_t x, std::uint32_t y) {
    const double reach = s.hw + 1.0;
    if (static_cast<double>(x) > std::max(s.ax, s.bx) + reach ||
        static_cast<double>(x) + 1.0 < std::min(s.ax, s.bx) - reach ||
        static_cast<double>(y) > std::max(s.ay, s.by) + reach ||
        static_cast<double>(y) + 1.0 < std::min(s.ay, s.by) - reach) {
        return 0.0;
    }
    const double dx = s.bx - s.ax;
    const double dy = s.by - s.ay;
    const double len = pymath::hypot(dx, dy);
    if (!(len > 0.0)) return 0.0;
    const double nx = -dy / len * s.hw;
    const double ny = dx / len * s.hw;
    std::vector<std::array<double, 2>> p{
        {s.ax - nx, s.ay - ny}, {s.bx - nx, s.by - ny}, {s.bx + nx, s.by + ny}, {s.ax + nx, s.ay + ny}};
    auto clip = [&p](int axis, double bound, double side) {
        std::vector<std::array<double, 2>> q;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const auto& c = p[i];
            const auto& e = p[(i + 1) % p.size()];
            const double dc = side * (c[static_cast<std::size_t>(axis)] - bound);
            const double de = side * (e[static_cast<std::size_t>(axis)] - bound);
            if (dc <= 0.0) q.push_back(c);
            if ((dc <= 0.0) != (de <= 0.0)) {
                const double t = dc / (dc - de);
                q.push_back({c[0] + ((e[0] - c[0]) * t), c[1] + ((e[1] - c[1]) * t)});
            }
        }
        p = std::move(q);
    };
    clip(0, static_cast<double>(x), -1.0);
    clip(0, static_cast<double>(x) + 1.0, 1.0);
    clip(1, static_cast<double>(y), -1.0);
    clip(1, static_cast<double>(y) + 1.0, 1.0);
    double twice = 0.0;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& c = p[i];
        const auto& e = p[(i + 1) % p.size()];
        twice += (c[0] * e[1]) - (e[0] * c[1]);
    }
    return std::abs(twice) * 0.5;
}

/// The shader's coverage (strokes.slang's stroke_area) step by step in float, as the twin of the GPU's.
[[nodiscard]] float shader_area(const Seg& s, std::uint32_t x, std::uint32_t y) {
    const auto ax = static_cast<float>(s.ax);
    const auto ay = static_cast<float>(s.ay);
    const float dx = static_cast<float>(s.bx) - ax;
    const float dy = static_cast<float>(s.by) - ay;
    const float len = std::sqrt((dx * dx) + (dy * dy));
    const auto hw = static_cast<float>(s.hw);
    if (!(len > 0.0F) || !(hw > 0.0F)) return 0.0F;
    const float ux = dx / len;
    const float uy = dy / len;
    const float nx = -uy;
    const float ny = ux;
    const float rx = (static_cast<float>(x) + 0.5F) - ax;
    const float ry = (static_cast<float>(y) + 0.5F) - ay;
    const float sc = (rx * ux) + (ry * uy);
    const float tc = (rx * nx) + (ry * ny);
    constexpr float half_diagonal = 0.70710678F;
    if (std::abs(tc) > hw + half_diagonal || sc < -half_diagonal || sc > len + half_diagonal) return 0.0F;
    std::vector<std::array<float, 2>> p;
    for (const auto& o :
         {std::array{-0.5F, -0.5F}, std::array{0.5F, -0.5F}, std::array{0.5F, 0.5F}, std::array{-0.5F, 0.5F}}) {
        p.push_back({(o[0] * ux) + (o[1] * uy), tc + ((o[0] * nx) + (o[1] * ny))});
    }
    auto clip = [&p](std::size_t axis, float bound, float side) {
        std::vector<std::array<float, 2>> q;
        for (std::size_t i = 0; i < p.size(); ++i) {
            const auto& c = p[i];
            const auto& e = p[(i + 1) % p.size()];
            const float dc = side * (c[axis] - bound);
            const float de = side * (e[axis] - bound);
            if (dc <= 0.0F) q.push_back(c);
            if ((dc <= 0.0F) != (de <= 0.0F)) {
                const float t = dc / (dc - de);
                q.push_back({c[0] + ((e[0] - c[0]) * t), c[1] + ((e[1] - c[1]) * t)});
            }
        }
        p = std::move(q);
    };
    clip(0, -sc, -1.0F);
    clip(0, len - sc, 1.0F);
    clip(1, -hw, -1.0F);
    clip(1, hw, 1.0F);
    float twice = 0.0F;
    for (std::size_t i = 0; i < p.size(); ++i) {
        const auto& c = p[i];
        const auto& e = p[(i + 1) % p.size()];
        twice += (c[0] * e[1]) - (e[0] * c[1]);
    }
    return std::min(std::abs(twice) * 0.5F, 1.0F);
}

/// The true stroke's coverage of pixel (x, y) by outline `o` (round joins: inside one of its rectangles, or within half
/// its width of a vertex where two of its segments join; an open outline's ends are butt), by 64 × 64 samples.
[[nodiscard]] double true_coverage(const Outline& o, std::span<const Seg> segs, std::uint32_t x, std::uint32_t y) {
    constexpr int n = 64;
    const std::size_t points = o.points.size();
    std::vector<std::array<double, 2>> joins;
    for (std::size_t i = 0; i < points; ++i) {
        if (o.closed || (i > 0 && i + 1 < points))
            joins.push_back({o.points[i][0] * density, o.points[i][1] * density});
    }
    const double hw = 0.5 * o.width * density;
    int inside = 0;
    for (int j = 0; j < n; ++j) {
        for (int i = 0; i < n; ++i) {
            const double px = static_cast<double>(x) + ((i + 0.5) / n);
            const double py = static_cast<double>(y) + ((j + 0.5) / n);
            bool in = false;
            for (const Seg& s : segs) {
                const double dx = s.bx - s.ax;
                const double dy = s.by - s.ay;
                const double len = pymath::hypot(dx, dy);
                const double along = (((px - s.ax) * dx) + ((py - s.ay) * dy)) / len;
                const double across = (((px - s.ax) * -dy) + ((py - s.ay) * dx)) / len;
                if (along >= 0.0 && along <= len && std::abs(across) <= hw) {
                    in = true;
                    break;
                }
            }
            for (std::size_t k = 0; !in && k < joins.size(); ++k) {
                in = pymath::hypot(px - joins[k][0], py - joins[k][1]) <= hw;
            }
            if (in) ++inside;
        }
    }
    return static_cast<double>(inside) / (n * n);
}

/// sRGB's transfer function and its inverse, in double.
[[nodiscard]] double linear_of(double c) { return c <= 0.04045 ? c / 12.92 : pymath::pow((c + 0.055) / 1.055, 2.4); }
[[nodiscard]] double srgb_of(double l) {
    return l <= 0.0031308 ? l * 12.92 : (1.055 * pymath::pow(l, 1.0 / 2.4)) - 0.055;
}

/// A source to blend: premultiplied rgb (0 to 1) and alpha.
struct Source {
    std::array<double, 3> rgb{};
    double alpha = 0;
};

/// The stroke's source in each variant, as strokes.slang's fragment stages give it: `mode_full` with the fields bound.
[[nodiscard]] Source source_of(const Seg& s, double a, bool over_objects, bool mode_full, std::uint32_t g) {
    const std::array<std::uint8_t, 3> level{s.colour.r, s.colour.g, s.colour.b};
    Source out;
    if (!mode_full) {
        for (std::size_t c = 0; c < 3; ++c) out.rgb[c] = (level[c] / 255.0) * a;
        out.alpha = a;
        return out;
    }
    if (over_objects) {
        const auto table = fx::mat_table();
        for (std::size_t c = 0; c < 3; ++c) out.rgb[c] = (table[(level[c] * 256U) + g] / 255.0) * a;
        out.alpha = a;
        return out;
    }
    const double grey = g / 255.0;
    const double ta = std::min(1.0, 1.1 * a);
    const double k = a * (0.9 + (0.2 * grey));
    out.alpha = ta * a;
    for (std::size_t c = 0; c < 3; ++c)
        out.rgb[c] = srgb_of(std::min(linear_of(level[c] / 255.0) * k, ta) / ta) * out.alpha;
    return out;
}

/// The scene's expected frame (rows from the top, R, G, B bytes) and, per pixel, how many blends reached it.
struct Expected {
    std::vector<std::uint8_t> rgb;
    std::vector<std::uint8_t> blends;
};

/// What the frame should hold: the boxes by their slots, then every segment blended in its order over the samples
/// its variant takes (8-bit storage after each blend), the coverage by the shader's own steps (`area`).
template <class Area>
[[nodiscard]] Expected expect(const Scene& scene, std::span<const Seg> segs, bool mode_full,
                              const fx::ScreenFields* fields, Area area) {
    Expected e;
    e.rgb.assign(std::size_t{frame_width} * frame_height * 3, 0);
    e.blends.assign(std::size_t{frame_width} * frame_height, 0);
    for (std::uint32_t y = 0; y < frame_height; ++y) {
        for (std::uint32_t x = 0; x < frame_width; ++x) {
            // The nearest box (the highest slot) holds the pixel.
            const Box* top = nullptr;
            for (const Box& b : scene.boxes) {
                if (x >= b.x0 && x < b.x1 && y >= b.y0 && y < b.y1 && (top == nullptr || b.slot > top->slot)) top = &b;
            }
            std::array<double, 3> dst{};
            if (top != nullptr) {
                dst = {static_cast<double>(top->colour.r), static_cast<double>(top->colour.g),
                       static_cast<double>(top->colour.b)};
            }
            const std::uint32_t slot = top != nullptr ? top->slot : 0;
            const bool objects = top != nullptr && top->objects;
            const std::uint32_t g = fields != nullptr ? fields->at(fx::Field::mat, x, y)[0] : 0;
            std::uint8_t blends = 0;
            for (const Seg& s : segs) {
                if (s.slot < slot) continue; // behind the pixel's nearest opaque primitive
                const double a = area(s, x, y);
                if (a <= 0.0) continue;
                const Source src = source_of(s, a, objects, mode_full, g);
                for (std::size_t c = 0; c < 3; ++c) {
                    dst[c] = std::round(((src.rgb[c] * 255.0) + (dst[c] * (1.0 - src.alpha))));
                }
                ++blends;
            }
            const std::size_t at = (std::size_t{y} * frame_width) + x;
            for (std::size_t c = 0; c < 3; ++c)
                e.rgb[(at * 3) + c] = static_cast<std::uint8_t>(std::clamp(dst[c], 0.0, 255.0));
            e.blends[at] = blends;
        }
    }
    return e;
}

/// A world of no tiles whose one provider of set 1 is the fields'.
class FieldsScene final : public frame::FrameScene {
public:
    explicit FieldsScene(frame::GlobalProvider& fields) noexcept : globals_{&fields} {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return nullptr; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }

private:
    std::array<frame::GlobalProvider*, 1> globals_;
};

/// The test's pass: the boxes (objects front to back writing the objects bit, then the ground) and the segments
/// through ObjectStrokes, one segment a draw.
class StrokesPass final : public frame::Pass {
public:
    StrokesPass(const Scene& scene, std::vector<Seg> segs) : scene_(scene), segs_(std::move(segs)) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "strokes test"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        ATLAS_GPU_LET(shapes, frame::make_module(c, "frame_test_shapes", spirv::frame_test_shapes));
        shapes_ = std::move(shapes);
        ATLAS_GPU_LET(strokes, frame::make_module(c, "fx_strokes", spirv::fx_strokes));
        strokes_module_ = std::move(strokes);
        auto box_pipeline = [&](bool objects) -> gpu::Result<vk::raii::Pipeline> {
            gpu::GraphicsDesc desc{.label = {.name = objects ? "strokes test objects" : "strokes test ground"},
                                   .module = *shapes_,
                                   .vertex = "shapes_vs",
                                   .fragment = "shapes_fs",
                                   .layout = c.layout.pipeline_layout(),
                                   .target = c.pass.scene_target()};
            desc.depth_test = true;
            desc.depth_write = true;
            desc.depth_op = vk::CompareOp::eGreater;
            if (objects) {
                desc.stencil_test = true;
                desc.stencil = {.failOp = vk::StencilOp::eKeep,
                                .passOp = vk::StencilOp::eReplace,
                                .depthFailOp = vk::StencilOp::eKeep,
                                .compareOp = vk::CompareOp::eAlways,
                                .compareMask = 0,
                                .writeMask = shaders::OBJECTS_BIT,
                                .reference = shaders::OBJECTS_BIT};
            }
            if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
            return frame::make_pipeline(c, desc, frame::Overdraw::opaque);
        };
        ATLAS_GPU_LET(objects, box_pipeline(true));
        ATLAS_GPU_LET(ground, box_pipeline(false));
        objects_ = std::move(objects);
        ground_ = std::move(ground);
        return strokes_.prepare(c, *strokes_module_, "strokes_check_vs", c.layout.pipeline_layout(), "test");
    }
    void release() noexcept override {
        strokes_.release();
        objects_.clear();
        ground_.clear();
        shapes_.clear();
        strokes_module_.clear();
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        const vk::raii::CommandBuffer& cmd = r.frame.cmd;
        auto push = [&](const auto& p) {
            cmd.pushConstants(
                r.layout.pipeline_layout(), frame::push_stages, 0,
                vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
        };
        if (phase == 1 || phase == 2) {
            std::vector<const Box*> boxes;
            for (const Box& b : scene_.boxes) {
                if (b.objects == (phase == 1)) boxes.push_back(&b);
            }
            std::ranges::sort(boxes, [](const Box* a, const Box* b) { return a->slot > b->slot; }); // front to back
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, phase == 1 ? *objects_ : *ground_);
            for (const Box* b : boxes) {
                const auto x0 = static_cast<float>(b->x0 / density);
                const auto y0 = static_cast<float>(b->y0 / density);
                const auto x1 = static_cast<float>(b->x1 / density);
                const auto y1 = static_cast<float>(b->y1 / density);
                for (const std::array<float, 6>& t :
                     {std::array{x0, y0, x1, y0, x0, y1}, std::array{x1, y0, x1, y1, x0, y1}}) {
                    layouts::frame_test_shapes::ShapePush p{};
                    p.a_b = {t[0], t[1], t[2], t[3]};
                    p.c_slot = {t[4], t[5], 0.0F, 0.0F};
                    p.colour = {static_cast<float>(b->colour.r) / 255.0F, static_cast<float>(b->colour.g) / 255.0F,
                                static_cast<float>(b->colour.b) / 255.0F, 1.0F};
                    p.slot = {b->slot, 0U, 0U, 0U};
                    push(p);
                    cmd.draw(3, 1, 0, 0);
                }
            }
            return;
        }
        strokes_.record(cmd, [&](vk::Pipeline pipeline) {
            (void)pipeline;
            for (const Seg& s : segs_) {
                layouts::fx_strokes::StrokePush p{};
                p.ends = {static_cast<float>(s.ax / density), static_cast<float>(s.ay / density),
                          static_cast<float>(s.bx / density), static_cast<float>(s.by / density)};
                p.colour = {static_cast<float>(s.colour.r) / 255.0F, static_cast<float>(s.colour.g) / 255.0F,
                            static_cast<float>(s.colour.b) / 255.0F, 1.0F};
                p.style = {static_cast<float>(2.0 * s.hw / density), 0.0F, 0.0F, 0.0F};
                p.slot = {s.slot, 0U, 0U, 0U};
                push(p);
                cmd.draw(6, 1, 0, 0);
                r.counters.draw("stroke", 1);
            }
        });
    }

private:
    static constexpr std::array<frame::Phase, 3> phase_list{
        frame::Phase{.slot = frame::Slot::objects, .order = 0, .id = 1},
        frame::Phase{.slot = frame::Slot::ground, .order = 0, .id = 2},
        frame::Phase{.slot = frame::Slot::translucent, .order = 0, .id = 3}};
    const Scene& scene_;
    std::vector<Seg> segs_;
    vk::raii::ShaderModule shapes_{nullptr};
    vk::raii::ShaderModule strokes_module_{nullptr};
    vk::raii::Pipeline objects_{nullptr};
    vk::raii::Pipeline ground_{nullptr};
    passes::ObjectStrokes strokes_;
};

/// The outlines: a quad and a triangle (the roofs' .25 and .45 px), a quad of .4 px, an open polyline.
[[nodiscard]] std::vector<Outline> outlines(Rgb fine, Rgb wide, Rgb cabin, Rgb open) {
    return {
        {.points = {{10.3, 5.2}, {50.7, 12.9}, {44.1, 40.3}, {6.6, 30.0}}, .width = 0.25, .colour = fine, .slot = 20},
        {.points = {{47.0, 21.5}, {66.2, 25.1}, {55.5, 48.8}}, .width = 0.45, .colour = wide, .slot = 30},
        {.points = {{25.0, 18.0}, {40.0, 24.0}, {36.0, 34.0}, {21.0, 28.0}}, .width = 0.4, .colour = cabin, .slot = 40},
        {.points = {{2.0, 50.0}, {20.0, 45.5}, {40.0, 54.0}},
         .closed = false,
         .width = 0.3,
         .colour = open,
         .slot = 50}};
}

/// Draws `scene` in `mode` and returns the frame's R, G, B bytes (and, through `latest`, the fields the frame read).
struct Drawn {
    std::vector<std::uint8_t> rgb;
    bool fields_on = false;
    std::shared_ptr<const fx::ScreenFields> fields;
    std::vector<std::string> messages;
};

[[nodiscard]] Drawn draw(test::Session& session, const Scene& scene, frame::Mode mode, bool weather = true) {
    Drawn out;
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE_OR_RETURN(out, cache.has_value(), cache.error().text());
    auto made = fx::FieldCache::create(device, {.threads = 2});
    ATLAS_REQUIRE_OR_RETURN(out, made.has_value(), made.error().text());
    fx::FieldCache& fields = **made;
    FieldsScene world(fields);
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<StrokesPass>(scene, segments_of(scene)));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes),
                                               {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = 0});
    ATLAS_REQUIRE_OR_RETURN(out, builder.has_value(), builder.error().text());
    auto output = platform::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2);
    ATLAS_REQUIRE_OR_RETURN(out, output.has_value(), output.error().text());
    // A moment with weather, where the renders give the objects group its `mat` (mat.slang's mat_on).
    style::ThemeBlock theme{};
    theme.flags = weather ? style::theme_flag::weather : 0U;
    (*builder)->set_inputs({.camera = standin::board_view(frame_width, frame_height, density),
                            .theme = theme,
                            .mode = mode,
                            .scene = &world});
    auto renderer = gpu::Renderer::create(device, **output, **builder, device.facts().choice);
    ATLAS_REQUIRE_OR_RETURN(out, renderer.has_value(), renderer.error().text());
    for (int n = 0; n < 4; ++n) {
        auto report = (*renderer)->draw(0);
        ATLAS_REQUIRE_OR_RETURN(out, report.has_value(), report.error().text());
        fields.worker().wait_idle();
    }
    (*renderer)->drain();
    out.fields_on = ((*builder)->state().block.info.x & shaders::FRAME_FIELDS) != 0U;
    out.fields = fields.worker().latest();
    auto pixels = (*output)->read((*output)->latest());
    ATLAS_REQUIRE_OR_RETURN(out, pixels.has_value(), pixels.error().text());
    out.rgb.reserve(std::size_t{frame_width} * frame_height * 3);
    for (std::size_t i = 0; i + 3 < pixels->rgba.size(); i += 4) {
        out.rgb.insert(out.rgb.end(), pixels->rgba.begin() + static_cast<std::ptrdiff_t>(i),
                       pixels->rgba.begin() + static_cast<std::ptrdiff_t>(i + 3));
    }
    out.messages = session.take_messages();
    renderer->reset();
    return out;
}

/// How the drawn frame compares with the expected one: pixels beyond a level a blend, and the largest difference.
struct Compared {
    std::size_t beyond = 0;
    int largest = 0;
    std::size_t stroked = 0;
    std::string first;
};

[[nodiscard]] Compared compare(const Drawn& d, const Expected& e) {
    Compared c;
    for (std::size_t at = 0; at < e.blends.size(); ++at) {
        int diff = 0;
        for (std::size_t k = 0; k < 3; ++k) {
            diff =
                std::max(diff, std::abs(static_cast<int>(d.rgb[(at * 3) + k]) - static_cast<int>(e.rgb[(at * 3) + k])));
        }
        if (e.blends[at] != 0) ++c.stroked;
        c.largest = std::max(c.largest, diff);
        if (std::cmp_greater(diff, e.blends[at])) {
            if (c.beyond == 0) {
                c.first = std::format("({}, {}): {} {} {} against {} {} {} after {} blends", at % frame_width,
                                      at / frame_width, d.rgb[at * 3], d.rgb[(at * 3) + 1], d.rgb[(at * 3) + 2],
                                      e.rgb[at * 3], e.rgb[(at * 3) + 1], e.rgb[(at * 3) + 2], e.blends[at]);
            }
            ++c.beyond;
        }
    }
    return c;
}

} // namespace

TEST_CASE("thin strokes cover each pixel by the exact area of their segments, and the round joins' residual is "
          "counted at the corners") {
    auto session = test::open_session();
    if (!session) return;
    const Rgb white{.r = 255, .g = 255, .b = 255};
    Scene scene{
        .boxes =
            {{.x0 = 0, .y0 = 0, .x1 = frame_width, .y1 = frame_height, .colour = {.r = 0, .g = 0, .b = 0}, .slot = 0}},
        .outlines = outlines(white, white, white, white)};
    const std::vector<Seg> segs = segments_of(scene);

    // The shader's steps in float against the exact area in double, at every pixel of every segment.
    double worst = 0.0;
    for (const Seg& s : segs) {
        for (std::uint32_t y = 0; y < frame_height; ++y) {
            for (std::uint32_t x = 0; x < frame_width; ++x) {
                worst = std::max(worst, std::abs(static_cast<double>(shader_area(s, x, y)) - exact_area(s, x, y)));
            }
        }
    }
    MESSAGE("the shader's coverage within " << worst << " of the exact area");
    CHECK(worst < 1.0 / 4096.0);

    const Drawn drawn = draw(*session, scene, frame::Mode::structural);
    ATLAS_REQUIRE(drawn.rgb.size() == std::size_t{frame_width} * frame_height * 3);
    CHECK_MESSAGE(drawn.messages.empty(), (drawn.messages.empty() ? std::string() : drawn.messages.front()));
    const Expected e = expect(scene, segs, false, nullptr, [](const Seg& s, std::uint32_t x, std::uint32_t y) {
        return static_cast<double>(shader_area(s, x, y));
    });
    const Compared c = compare(drawn, e);
    MESSAGE(c.stroked << " pixels stroked; the largest difference " << c.largest << " levels");
    CHECK_MESSAGE(c.beyond == 0, c.beyond << " pixels beyond a level a blend; the first " << c.first);

    // The residual: the butt segments against the true stroke, outline by outline.
    std::size_t residual = 0;
    std::size_t off_corner = 0;
    double largest = 0.0;
    for (std::uint32_t y = 0; y < frame_height; ++y) {
        for (std::uint32_t x = 0; x < frame_width; ++x) {
            double butt = 1.0;
            double truth = 1.0;
            bool near = false;
            for (std::size_t o = 0; o < scene.outlines.size(); ++o) {
                std::vector<Seg> own;
                for (const Seg& s : segs) {
                    if (s.outline == o) own.push_back(s);
                }
                bool touched = false;
                for (const Seg& s : own) {
                    const double a = exact_area(s, x, y);
                    if (a > 0.0) touched = true;
                    butt *= 1.0 - a;
                }
                if (!touched) continue;
                truth *= 1.0 - true_coverage(scene.outlines[o], own, x, y);
                for (const auto& p : scene.outlines[o].points) {
                    const double hw = 0.5 * scene.outlines[o].width * density;
                    near =
                        near || pymath::hypot((x + 0.5) - (p[0] * density), (y + 0.5) - (p[1] * density)) <= hw + 1.5;
                }
            }
            const double diff = std::abs(butt - truth) * 255.0;
            if (diff > 2.0) {
                ++residual;
                if (!near) ++off_corner;
                largest = std::max(largest, diff);
            }
        }
    }
    std::size_t corners = 0;
    for (const Outline& o : scene.outlines) corners += o.closed ? o.points.size() : o.points.size() - 2;
    MESSAGE("the round joins' residual (butt segments, untrimmed): "
            << residual << " pixels at " << corners << " joins differ from the true stroke by more than 2 "
            << "levels, at most " << largest);
    CHECK(off_corner == 0);
}

TEST_CASE("thin strokes take the ground's variant and the objects' by the objects bit, behind nearer objects and over "
          "their own, thinned and in mat's colours in full mode") {
    auto session = test::open_session();
    if (!session) return;
    const Rgb fine{.r = 0x6F, .g = 0x69, .b = 0x5F};
    const Rgb cabin{.r = 0x2A, .g = 0x2C, .b = 0x30};
    const Rgb open{.r = 0x9C, .g = 0x5B, .b = 0x3E};
    Scene scene{.boxes = {{.x0 = 0,
                           .y0 = 0,
                           .x1 = frame_width,
                           .y1 = frame_height,
                           .colour = {.r = 0xE4, .g = 0xDD, .b = 0xD0},
                           .slot = 0},
                          {.x0 = 20,
                           .y0 = 20,
                           .x1 = 90,
                           .y1 = 70,
                           .colour = {.r = 0xB8, .g = 0x71, .b = 0x4A},
                           .slot = 10,
                           .objects = true},
                          {.x0 = 110,
                           .y0 = 40,
                           .x1 = 180,
                           .y1 = 120,
                           .colour = {.r = 0x7A, .g = 0x6A, .b = 0x5C},
                           .slot = 30,
                           .objects = true}},
                .outlines = outlines(fine, fine, cabin, open)};
    const std::vector<Seg> segs = segments_of(scene);
    auto area = [](const Seg& s, std::uint32_t x, std::uint32_t y) {
        return static_cast<double>(shader_area(s, x, y));
    };

    // Structural mode, full mode at a moment with weather, and full mode at one without (no mat: the renders give the
    // group no filter there).
    for (const auto& [mode, weather] : {std::pair{frame::Mode::structural, true}, std::pair{frame::Mode::full, true},
                                        std::pair{frame::Mode::full, false}}) {
        const bool full = mode == frame::Mode::full;
        const bool mat = full && weather;
        CAPTURE(full);
        CAPTURE(weather);
        const Drawn drawn = draw(*session, scene, mode, weather);
        ATLAS_REQUIRE(drawn.rgb.size() == std::size_t{frame_width} * frame_height * 3);
        CHECK_MESSAGE(drawn.messages.empty(), (drawn.messages.empty() ? std::string() : drawn.messages.front()));
        CHECK(drawn.fields_on == full);
        if (mat) ATLAS_REQUIRE(drawn.fields != nullptr);
        const Expected e = expect(scene, segs, mat, mat ? drawn.fields.get() : nullptr, area);
        const Compared c = compare(drawn, e);
        MESSAGE(std::string(mat ? "full" : (full ? "full without weather" : "structural"))
                << ": " << c.stroked << " pixels stroked; the largest difference " << c.largest << " levels");
        CHECK_MESSAGE(c.beyond == 0, c.beyond << " pixels beyond a level a blend; the first " << c.first);
        if (mat) {
            // The thinning shows: the ground variant's model with plain alpha would differ.
            const Expected plain = expect(scene, segs, false, nullptr, area);
            const Compared unthinned = compare(drawn, plain);
            MESSAGE("against the unthinned model, " << unthinned.beyond << " pixels differ");
            CHECK(unthinned.beyond > 100);
        }
    }
}
#endif
