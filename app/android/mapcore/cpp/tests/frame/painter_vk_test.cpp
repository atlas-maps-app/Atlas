// Painter's order as depth (plan-vector D6, K7's acceptance): 2,000 random overlapping triangles, some opaque and some
// translucent, each the one primitive of an item at its own depth in the item table, ranked by the frame (their painter
// slots are the ranks' first slots), drawn in D6's two passes (the opaque ones front to back with the depth test and
// writes, the translucent ones after in painter's order with writes off) equal the same triangles drawn in strict
// painter's order without depth, bit for bit, at one sample and at the device's multisampling; and the owner mode's map
// gives each pixel the owner of the shape strict painter's order leaves on top there. The owner and overdraw twins of a
// pass whose vertex stage has varyings are made without the validation layer's warning about the outputs their
// fragment stages leave unread, which a pass's own pipeline leaving one unread still gets.
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frame/test_shapes.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "scene/ItemTable.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <format>
#include <memory>
#include <numeric>
#include <random>
#include <ranges>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

using namespace atlas;

#if !defined(__ANDROID__)
namespace {

/// One triangle: its corners (CSS px), colour (its alpha its opacity) and the item row it is the primitive of.
struct Shape {
    std::array<float, 6> corners{};
    std::array<float, 4> colour{};
    bool opaque = true;
    std::uint32_t row = 0;
    std::uint32_t slot = 0;
};

/// The shapes and their items: item i stands on the camera's axis at a depth of its own, so the ranks put the shapes
/// in an order of their own, far first.
struct Shapes {
    std::vector<Shape> shapes;
    scene::ItemTable items;
};

[[nodiscard]] Shapes make_shapes(std::size_t n, float width, float height, std::uint32_t seed) {
    Shapes s;
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> x(-0.15F * width, 1.15F * width);
    std::uniform_real_distribution<float> y(-0.15F * height, 1.15F * height);
    std::uniform_real_distribution<double> height_of(-800.0, 900.0);
    std::uniform_int_distribution<int> level(0, 255);
    std::uniform_int_distribution<int> kind(0, 9);
    std::uniform_int_distribution<int> opacity(40, 215);
    for (std::size_t i = 0; i < n; ++i) {
        Shape sh;
        // A triangle about a random centre, at most a third of the frame across: they overlap many times over.
        const float cx = x(rng);
        const float cy = y(rng);
        std::uniform_real_distribution<float> d(-width / 6.0F, width / 6.0F);
        sh.corners = {cx + d(rng), cy + d(rng), cx + d(rng), cy + d(rng), cx + d(rng), cy + d(rng)};
        sh.opaque = kind(rng) < 6;
        sh.colour = {static_cast<float>(level(rng)) / 255.0F, static_cast<float>(level(rng)) / 255.0F,
                     static_cast<float>(level(rng)) / 255.0F,
                     sh.opaque ? 1.0F : static_cast<float>(opacity(rng)) / 255.0F};
        // Its item: on the axis (the camera looks straight down from 1 km), one slot in every band.
        const double h = height_of(rng);
        sh.row = s.items.add({.x = 0,
                              .y = h,
                              .z = 0,
                              .offset = 0,
                              .cut_x = 0,
                              .cut_z = 0,
                              .order = scene::order_of(0, i),
                              .kind = 0,
                              .flags = 0,
                              .slots = {1, 1, 1, 1},
                              .min_depth = 2.0F,
                              .radius = 1.0F,
                              .tile = 1,
                              .owner = scene::owner_key(2, 1000 + i)});
        s.shapes.push_back(sh);
    }
    return s;
}

/// A scene of the shapes' items alone.
class ShapesScene final : public frame::FrameScene {
public:
    explicit ShapesScene(const scene::ItemTable& items) noexcept : items_(items) {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return &items_; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }

private:
    const scene::ItemTable& items_;
};

/// Draws the shapes at their items' slots: in D6's two passes, or (strict) every one in painter's order without depth.
class ShapesPass final : public frame::Pass {
public:
    ShapesPass(Shapes& shapes, bool strict) : shapes_(shapes), strict_(strict) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "shapes"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override {
        return strict_ ? std::span<const frame::Phase>(strict_phases) : std::span<const frame::Phase>(two_passes);
    }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        ATLAS_GPU_LET(module, frame::make_module(c, "frame_test_shapes", spirv::frame_test_shapes));
        module_ = std::move(module);
        auto pipeline = [&](bool opaque) -> gpu::Result<vk::raii::Pipeline> {
            gpu::GraphicsDesc desc{.label = {.name = opaque ? "shapes opaque" : "shapes translucent"},
                                   .module = *module_,
                                   .vertex = "shapes_vs",
                                   .fragment = "shapes_fs",
                                   .layout = c.layout.pipeline_layout(),
                                   .target = c.pass.scene_target()};
            desc.depth_test = !strict_;
            desc.depth_write = !strict_ && opaque;
            desc.depth_op = vk::CompareOp::eLess;
            if (!opaque) {
                desc.blends[0] = {.enable = true,
                                  .src_colour = vk::BlendFactor::eSrcAlpha,
                                  .dst_colour = vk::BlendFactor::eOneMinusSrcAlpha,
                                  .src_alpha = vk::BlendFactor::eZero,
                                  .dst_alpha = vk::BlendFactor::eOne,
                                  .mask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                          vk::ColorComponentFlagBits::eB};
            }
            if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
            return frame::make_pipeline(c, desc, opaque ? frame::Overdraw::opaque : frame::Overdraw::translucent);
        };
        ATLAS_GPU_LET(opaque, pipeline(true));
        ATLAS_GPU_LET(translucent, pipeline(false));
        opaque_ = std::move(opaque);
        translucent_ = std::move(translucent);
        return {};
    }
    void release() noexcept override {
        opaque_.clear();
        translucent_.clear();
        module_.clear();
    }
    void update(const frame::FrameState& state) override {
        // Each shape at its item's first slot, as the ranks gave it this frame.
        for (Shape& s : shapes_.shapes) s.slot = state.ranks != nullptr ? state.ranks->first_slot()[s.row] : 0;
        order_.clear();
        order_.resize(shapes_.shapes.size());
        std::ranges::iota(order_, 0U);
        std::ranges::sort(
            order_, [&](std::uint32_t a, std::uint32_t b) { return shapes_.shapes[a].slot < shapes_.shapes[b].slot; });
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        const vk::raii::CommandBuffer& cmd = r.frame.cmd;
        auto draw = [&](const Shape& s) {
            layouts::frame_test_shapes::ShapePush p{};
            p.a_b = {s.corners[0], s.corners[1], s.corners[2], s.corners[3]};
            p.c_slot = {s.corners[4], s.corners[5], 0.0F, 0.0F};
            p.colour = s.colour;
            p.slot = {s.slot, 0U, 0U, 0U};
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, s.opaque ? *opaque_ : *translucent_);
            cmd.pushConstants(
                r.layout.pipeline_layout(), frame::push_stages, 0,
                vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
            cmd.draw(3, 1, 0, 0);
            r.counters.draw(s.opaque ? "opaque" : "translucent", 1);
        };
        if (phase == 3) { // strict: every shape in painter's order
            for (const std::uint32_t i : order_) draw(shapes_.shapes[i]);
        } else if (phase == 1) { // the opaque ones, front to back
            for (const std::uint32_t i : std::views::reverse(order_)) {
                if (shapes_.shapes[i].opaque) draw(shapes_.shapes[i]);
            }
        } else { // the translucent ones, in painter's order
            for (const std::uint32_t i : order_) {
                if (!shapes_.shapes[i].opaque) draw(shapes_.shapes[i]);
            }
        }
    }

private:
    static constexpr std::array<frame::Phase, 2> two_passes{
        frame::Phase{.slot = frame::Slot::objects, .order = 0, .id = 1},
        frame::Phase{.slot = frame::Slot::translucent, .order = 0, .id = 2}};
    static constexpr std::array<frame::Phase, 1> strict_phases{
        frame::Phase{.slot = frame::Slot::translucent, .order = 0, .id = 3}};
    Shapes& shapes_;
    bool strict_;
    std::vector<std::uint32_t> order_;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline opaque_{nullptr};
    vk::raii::Pipeline translucent_{nullptr};
};

/// The frame of `shapes` drawn with `config`, two-pass or strict, in colour or in the owner mode: its 8-bit pixels, and
/// in the owner mode its owner map.
struct Drawn {
    platform::Pixels pixels;
    std::vector<std::uint64_t> owners;
    std::uint64_t draws = 0;
};

[[nodiscard]] std::optional<Drawn> draw(test::Session& session, const gpu::PipelineCache& cache, Shapes& shapes,
                                        const gpu::Configuration& config, bool strict, bool owners) {
    const gpu::Device& device = *session.device;
    const vk::Extent2D extent{.width = 160, .height = 120};
    auto output = platform::Offscreen::create(device, extent, 2);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, output.has_value(), output.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<ShapesPass>(shapes, strict));
    auto builder = frame::FrameBuilder::create(
        device, cache, std::move(passes), {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = 0, .owners = owners});
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, builder.has_value(), builder.error().text());
    ShapesScene scene(shapes.items);
    // Straight down from 1 km over the origin, 2 px a dp.
    (*builder)->set_inputs(
        {.camera = standin::board_view(extent.width, extent.height, 2.0, 0.0, 0.0, 1000.0), .scene = &scene});
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, renderer.has_value(), renderer.error().text());
    auto report = (*renderer)->draw(0);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, report.has_value(), report.error().text());
    (*renderer)->drain();
    auto pixels = (*output)->read((*output)->latest());
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, pixels.has_value(), pixels.error().text());
    Drawn d;
    d.pixels = std::move(*pixels);
    d.draws = (*builder)->total().draws;
    if (owners) {
        d.owners.resize(static_cast<std::size_t>(extent.width) * extent.height);
        (*builder)->owner_map(d.pixels.rgba, d.owners);
    }
    renderer->reset();
    return d;
}

/// One pipeline: varying_vs (the colour as a varying) with `fragment`, which reads it (varying_fs) or leaves it unread
/// (shapes_fs: stages mismatched on purpose). Nothing is drawn: the pipeline and its twins are what is checked.
class VaryingsPass final : public frame::Pass {
public:
    explicit VaryingsPass(const char* fragment) noexcept : fragment_(fragment) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "varyings"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return one_phase; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& c) override {
        ATLAS_GPU_LET(module, frame::make_module(c, "frame_test_shapes", spirv::frame_test_shapes));
        module_ = std::move(module);
        gpu::GraphicsDesc desc{.label = {.name = "varyings"},
                               .module = *module_,
                               .vertex = "varying_vs",
                               .fragment = fragment_,
                               .layout = c.layout.pipeline_layout(),
                               .target = c.pass.scene_target()};
        desc.depth_test = true;
        desc.depth_write = true;
        desc.depth_op = vk::CompareOp::eLess;
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        ATLAS_GPU_LET(pipeline, frame::make_pipeline(c, desc, frame::Overdraw::opaque));
        pipeline_ = std::move(pipeline);
        return {};
    }
    void release() noexcept override {
        pipeline_.clear();
        module_.clear();
    }
    void record(const frame::Recording& r, std::uint32_t phase) override {
        (void)r;
        (void)phase;
    }

private:
    static constexpr std::array<frame::Phase, 1> one_phase{
        frame::Phase{.slot = frame::Slot::objects, .order = 0, .id = 1}};
    const char* fragment_;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// Makes the registered passes and a VaryingsPass with `fragment` in a frame with `options`, its pipelines and twins
/// made for an offscreen output at one sample: whether they were.
[[nodiscard]] bool make_varyings(const gpu::Device& device, const gpu::PipelineCache& cache, const char* fragment,
                                 const frame::FrameOptions& options) {
    auto output = platform::Offscreen::create(device, vk::Extent2D{.width = 64, .height = 48}, 2);
    ATLAS_REQUIRE_OR_RETURN(false, output.has_value(), output.error().text());
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes.push_back(std::make_unique<VaryingsPass>(fragment));
    auto builder = frame::FrameBuilder::create(device, cache, std::move(passes), options);
    ATLAS_REQUIRE_OR_RETURN(false, builder.has_value(), builder.error().text());
    auto renderer = gpu::Renderer::create(device, **output, **builder,
                                          {.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct});
    ATLAS_REQUIRE_OR_RETURN(false, renderer.has_value(), renderer.error().text());
    renderer->reset();
    return true;
}

} // namespace

TEST_CASE("a pass with varyings has its owner and overdraw twins made without the layer's unread-output warning, which "
          "its own pipeline still gets when it leaves one unread") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const gpu::Messages& messages = session->instance->messages();
    auto unread = [](const std::vector<std::string>& list) {
        return std::ranges::count_if(list, [](const std::string& m) { return m.contains("OutputNotConsumed"); });
    };

    // The pass's own pipeline reads its varying: nothing to say, nothing dropped.
    std::size_t quieted = messages.quieted();
    CHECK(make_varyings(device, *cache, "varying_fs", {}));
    CHECK(messages.quieted() == quieted);
    auto said = session->take_messages();
    CHECK_MESSAGE(said.empty(), (said.empty() ? std::string() : said.front()));

    // Its twins read nothing of it: the layer warns while each is made, and the warning is dropped there.
    for (const frame::FrameOptions& options :
         {frame::FrameOptions{.owners = true}, frame::FrameOptions{.overdraw = true}}) {
        quieted = messages.quieted();
        CHECK(make_varyings(device, *cache, "varying_fs", options));
        CHECK(messages.quieted() > quieted);
        said = session->take_messages();
        CHECK_MESSAGE(said.empty(), (said.empty() ? std::string() : said.front()));
    }

    // A pass's own pipeline that leaves the varying unread is still reported.
    quieted = messages.quieted();
    CHECK(make_varyings(device, *cache, "shapes_fs", {}));
    CHECK(messages.quieted() == quieted);
    said = session->take_messages();
    CHECK(unread(said) >= 1);
    MESSAGE("the mismatched pipeline: " << (said.empty() ? std::string("(nothing)") : said.front()));
}

TEST_CASE("the two-pass painter depth draws 2,000 random overlapping shapes as strict painter's order does") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    Shapes shapes = make_shapes(2000, 80.0F, 60.0F, 20261019);
    std::vector<gpu::Configuration> configs{{.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct}};
    configs.push_back(device.facts().choice); // the device's own: 4x on lavapipe
    for (const gpu::Configuration& config : configs) {
        const auto two_passes = draw(*session, *cache, shapes, config, false, false);
        const auto strict_order = draw(*session, *cache, shapes, config, true, false);
        if (!two_passes || !strict_order) {
            FAIL("a frame was not drawn");
            return;
        }
        const Drawn* two_pass = &*two_passes;
        const Drawn* strict = &*strict_order;
        REQUIRE(two_pass->pixels.rgba.size() == strict->pixels.rgba.size());
        std::size_t differ = 0;
        std::size_t painted = 0;
        for (std::size_t i = 0; i < strict->pixels.rgba.size(); i += 4) {
            if (std::memcmp(&two_pass->pixels.rgba[i], &strict->pixels.rgba[i], 3) != 0) ++differ;
            if (strict->pixels.rgba[i] != 0 || strict->pixels.rgba[i + 1] != 0 || strict->pixels.rgba[i + 2] != 0) {
                ++painted;
            }
        }
        const auto samples = static_cast<std::uint32_t>(config.samples());
        CHECK_MESSAGE(differ == 0, samples << " sample(s): " << differ << " pixels differ");
        CHECK(painted > strict->pixels.rgba.size() / 4 * 9 / 10); // the shapes cover the frame many times
        CHECK(two_pass->draws == strict->draws);
        MESSAGE(samples << " sample(s): " << shapes.shapes.size() << " shapes, " << painted
                        << " pixels painted, the two passes equal strict painter's order on every one");
    }
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}

TEST_CASE("the owner mode maps each pixel to the owner of the shape strict painter's order leaves on top") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    Shapes shapes = make_shapes(2000, 80.0F, 60.0F, 7);
    const gpu::Configuration one{.config = gpu::FrameConfig::x1, .target = gpu::CompositeTarget::direct};
    const auto two_passes = draw(*session, *cache, shapes, one, false, true);
    const auto strict_order = draw(*session, *cache, shapes, one, true, true);
    if (!two_passes || !strict_order) {
        FAIL("a frame was not drawn");
        return;
    }
    const Drawn* two_pass = &*two_passes;
    const Drawn* strict = &*strict_order;
    std::size_t owned = 0;
    std::size_t differ = 0;
    for (std::size_t i = 0; i < strict->owners.size(); ++i) {
        if (two_pass->owners[i] != strict->owners[i]) ++differ;
        if (strict->owners[i] != 0) {
            ++owned;
            CHECK((strict->owners[i] >> 56U) == 2U); // a way's
        }
    }
    CHECK(differ == 0);
    CHECK(owned > strict->owners.size() * 9 / 10);
    // Every owner is one of the shapes', and the topmost shape of a pixel is the latest whose slot is drawn there.
    std::vector<std::uint64_t> keys;
    keys.reserve(shapes.shapes.size());
    for (std::size_t i = 0; i < shapes.shapes.size(); ++i) keys.push_back(scene::owner_key(2, 1000 + i));
    std::ranges::sort(keys);
    std::size_t known = 0;
    for (const std::uint64_t o : strict->owners) known += (o == 0 || std::ranges::binary_search(keys, o)) ? 1U : 0U;
    CHECK(known == strict->owners.size());
    std::vector<std::uint64_t> distinct(strict->owners.begin(), strict->owners.end());
    std::ranges::sort(distinct);
    distinct.erase(std::ranges::unique(distinct).begin(), distinct.end());
    CHECK(distinct.size() > 200);
    // At pixel centres where no shape that might be on top has an edge near (a twentieth of a pixel), the owner is the
    // owner of the covering shape with the latest slot, as the CPU finds it.
    const std::uint32_t width = strict->pixels.width;
    const double density = 2.0;
    std::size_t checked = 0;
    std::size_t wrong = 0;
    for (std::size_t i = 0; i < strict->owners.size(); i += 3) {
        const std::size_t column = i % width;
        const std::size_t row = i / width;
        const double px = (static_cast<double>(column) + 0.5) / density;
        const double py = (static_cast<double>(row) + 0.5) / density;
        // Per shape: how far inside its nearest edge the centre lies (CSS px, negative outside).
        const Shape* top = nullptr;
        std::uint32_t unsure = 0; // the latest slot whose shape's edge passes near the centre, plus one
        for (const Shape& sh : shapes.shapes) {
            const auto& c = sh.corners;
            auto at = [&c](std::size_t k) {
                return std::array<double, 2>{static_cast<double>(c[2 * k]), static_cast<double>(c[(2 * k) + 1])};
            };
            const auto a = at(0);
            const auto b = at(1);
            const auto d = at(2);
            const double area = ((b[0] - a[0]) * (d[1] - a[1])) - ((b[1] - a[1]) * (d[0] - a[0]));
            if (area == 0.0) continue;
            const double sign = area > 0 ? 1.0 : -1.0;
            double inner = 1e9;
            for (std::size_t k = 0; k < 3; ++k) {
                const auto p = at(k);
                const auto q = at((k + 1) % 3);
                const double len = std::sqrt(((q[0] - p[0]) * (q[0] - p[0])) + ((q[1] - p[1]) * (q[1] - p[1])));
                const double e = sign * (((q[0] - p[0]) * (py - p[1])) - ((q[1] - p[1]) * (px - p[0]))) / len;
                inner = std::min(inner, e);
            }
            if (inner >= 0.025) {
                if (top == nullptr || sh.slot > top->slot) top = &sh;
            } else if (inner > -0.025) {
                unsure = std::max(unsure, sh.slot + 1);
            }
        }
        if (unsure > (top == nullptr ? 0U : top->slot + 1)) continue; // a shape above might or might not cover it
        const std::uint64_t want =
            top == nullptr ? 0 : scene::owner_key(2, 1000 + static_cast<std::uint64_t>(top - shapes.shapes.data()));
        ++checked;
        if (strict->owners[i] != want) ++wrong;
    }
    CHECK(checked > 500);
    CHECK(wrong == 0);
    MESSAGE(distinct.size() << " owners; at " << checked << " pixel centres clear of every edge the owner is the CPU's "
                            << "topmost shape's");
    MESSAGE(owned << " of " << strict->owners.size() << " pixels owned; the two passes' owner map equals strict "
                  << "painter's order's");
    const auto messages = session->take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}
#endif
