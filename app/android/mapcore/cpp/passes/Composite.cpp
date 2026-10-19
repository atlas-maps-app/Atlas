// The composite (passes/Composite.hpp).
#include "passes/Composite.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/frame/composite.hpp"
#include "gen/spirv/frame/composite_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x1.hpp"
#include "gen/spirv/frame/composite_x1_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x1_loc1.hpp"
#include "gen/spirv/frame/composite_x1_loc1_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x2.hpp"
#include "gen/spirv/frame/composite_x2_glsl_composite_fs.hpp"
#include "gpu/Descriptors.hpp"
#include "twin/Twin.hpp"

#include <bit>
#include <format>
#include <string>

namespace atlas::passes {

namespace {

/// An embedded module and its symbol.
struct Spirv {
    std::string_view name;
    std::span<const std::uint32_t> words;
};

/// The composite's modules for the samples it reads, as Slang's own SPIR-V and through its GLSL route; `loc1` at one
/// sample in the dynamic route, where the composite writes colour attachment 1.
struct Modules {
    Spirv direct;
    Spirv glsl;
};

[[nodiscard]] gpu::Result<Modules> modules_for(vk::SampleCountFlagBits samples, bool loc1) {
    using S = vk::SampleCountFlagBits;
    switch (samples) {
    case S::e4:
        return Modules{
            .direct = {.name = "frame_composite", .words = spirv::frame_composite},
            .glsl = {.name = "frame_composite_glsl_composite_fs", .words = spirv::frame_composite_glsl_composite_fs}};
    case S::e2:
        return Modules{.direct = {.name = "frame_composite_x2", .words = spirv::frame_composite_x2},
                       .glsl = {.name = "frame_composite_x2_glsl_composite_fs",
                                .words = spirv::frame_composite_x2_glsl_composite_fs}};
    case S::e1:
        if (loc1) {
            return Modules{.direct = {.name = "frame_composite_x1_loc1", .words = spirv::frame_composite_x1_loc1},
                           .glsl = {.name = "frame_composite_x1_loc1_glsl_composite_fs",
                                    .words = spirv::frame_composite_x1_loc1_glsl_composite_fs}};
        }
        return Modules{.direct = {.name = "frame_composite_x1", .words = spirv::frame_composite_x1},
                       .glsl = {.name = "frame_composite_x1_glsl_composite_fs",
                                .words = spirv::frame_composite_x1_glsl_composite_fs}};
    default: return gpu::fail(std::format("the composite has no module for {} samples", vk::to_string(samples)));
    }
}

/// Java's Math.round of a float (the SVG parser folds a stop's opacity into its colour's alpha so: round(255 · a)).
[[nodiscard]] std::int32_t java_round(float a) noexcept {
    const auto bits = std::bit_cast<std::int32_t>(a);
    const std::int32_t biased = (bits & 0x7F80'0000) >> 23;
    const std::int32_t shift = (23 - 1 + 127) - biased;
    if ((shift & -32) == 0) {
        std::int32_t r = (bits & 0x007F'FFFF) | 0x0080'0000;
        if (bits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    return static_cast<std::int32_t>(a);
}

/// A colour's ARGB with alpha round(255 · opacity), as the parser folds a stop's opacity.
[[nodiscard]] std::uint32_t folded(hlsl::float4 rgb, float opacity) noexcept {
    const auto level = [](float c) { return static_cast<std::uint32_t>(java_round(c * 255.0F)); };
    const auto alpha = static_cast<std::uint32_t>(java_round(255.0F * opacity));
    return (alpha << 24U) | (level(rgb.x) << 16U) | (level(rgb.y) << 8U) | level(rgb.z);
}

} // namespace

gpu::Result<void> Composite::prepare(const frame::PassContext& context) {
    release();
    const gpu::MainPass& pass = context.pass;
    if (!pass.composite()) return gpu::fail("the composite draws in the composite form only (ONEPASS is not built)");
    const gpu::Configuration& c = pass.spec().config;
    const bool loc1 = c.samples() == vk::SampleCountFlagBits::e1 && c.route == gpu::Route::dynamic;
    ATLAS_GPU_LET(modules, modules_for(c.samples(), loc1));
    glsl_ = !context.device.facts().takes_read_without_format();
    ATLAS_GPU_LET(module, frame::make_module(context, modules.direct.name, modules.direct.words));
    module_ = std::move(module);
    if (glsl_) {
        ATLAS_GPU_LET(glsl, frame::make_module(context, modules.glsl.name, modules.glsl.words));
        glsl_module_ = std::move(glsl);
    }

    // Set 2: the samples and their stencil, as the subpass (or the local reads) give them.
    const vk::raii::Device& d = context.device.handle();
    const std::array inputs{
        gpu::Binding{
            .slot = 0, .type = vk::DescriptorType::eInputAttachment, .stages = vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{
            .slot = 1, .type = vk::DescriptorType::eInputAttachment, .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(inputs_layout, gpu::make_set_layout(d, inputs));
    inputs_layout_ = std::move(inputs_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{inputs};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 1));
    pool_ = std::move(pool);
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, inputs_layout_));
    inputs_ = std::move(set);
    gpu::SetWriter(*inputs_)
        .image(0, vk::DescriptorType::eInputAttachment, pass.colour_input(), pass.colour_input_layout())
        .image(1, vk::DescriptorType::eInputAttachment, pass.stencil_input(), pass.stencil_input_layout())
        .apply(d);
    const std::array<vk::DescriptorSetLayout, 1> extra{*inputs_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);

    const std::string names =
        glsl_ ? std::format("{} and {}", modules.direct.name, modules.glsl.name) : std::string(modules.direct.name);
    gpu::GraphicsDesc desc{.label = {.name = "composite", .module = names, .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "fullscreen_vs",
                           .fragment = glsl_ ? "main" : "composite_fs",
                           .fragment_module = glsl_ ? *glsl_module_ : vk::ShaderModule{},
                           .layout = *layout_,
                           .target = pass.composite_target()};
    if (desc.target.colour_count == 2) {
        desc.blends[0] = gpu::Blend::untouched(); // the scene's colour, which the composite only reads
        desc.blends[1] = gpu::Blend::opaque();
    }
    ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::none));
    pipeline_ = std::move(pipeline);
    return {};
}

void Composite::release() noexcept {
    pipeline_.clear();
    layout_.clear();
    inputs_.clear();
    pool_.clear();
    inputs_layout_.clear();
    glsl_module_.clear();
    module_.clear();
}

void Composite::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *inputs_, {});
    cmd.draw(3, 1, 0, 0);
    recording.counters.draw("composite", 1);
}

void Composite::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    (void)phase;
    const shaders::FrameLayers& l = state.block.layers;
    const std::uint32_t on = l.steps.x & shaders::COMPOSITE_DRAWN;
    const float width = state.block.camera.frame.x;
    const float height = state.block.camera.frame.y;
    out.layer(twin::Layer::objects);
    out.at(twin::Stream::after, out.root());
    // render/amsterdam.py:899: the haze by ground depth (ScreenLayers.hazeDef, hazeRect), as the composite applies it
    const auto stops = static_cast<std::size_t>(l.haze.w);
    if ((on & shaders::COMPOSITE_HAZE) != 0U && stops > 0) {
        std::array<float, 28> offsets{};
        std::array<std::uint32_t, 28> colours{};
        for (std::size_t i = 0; i < stops && i < offsets.size(); ++i) {
            offsets[i] = l.haze_offsets[i / 4][static_cast<int>(i % 4)];
            colours[i] = folded(l.haze_colour, l.haze_alphas[i / 4][static_cast<int>(i % 4)]);
        }
        const std::int32_t haze = out.gradient({.id = "haze",
                                                .radial = false,
                                                .coords = {0.0F, l.haze.x, 0.0F, l.haze.y, 0.0F},
                                                .units = twin::Units::user_space,
                                                .transformed = false,
                                                .transform = {1, 0, 0, 1, 0, 0},
                                                .spread = twin::Spread::pad,
                                                .offsets = std::span(offsets).first(stops),
                                                .colours = std::span(colours).first(stops)});
        out.rect({.fill = twin::Paint::gradient(haze)}, 0.0F, l.haze.x, width, l.haze.y - l.haze.x);
    }
    // render/amsterdam.py:964: fin's vignette over everything (its `vig` gradient: transparent to .55, then black)
    if ((on & shaders::COMPOSITE_VIGNETTE) != 0U && l.vignette.x > 0.0F) {
        const std::array<float, 2> offsets{0.55F, 1.0F};
        const hlsl::float4 black{0.0F, 0.0F, 0.0F, 0.0F};
        const std::array<std::uint32_t, 2> colours{folded(black, 0.0F), folded(black, l.vignette.x)};
        const std::int32_t vig = out.gradient({.id = "vig",
                                               .radial = true,
                                               .coords = {0.5F, 0.46F, 0.75F, 0.5F, 0.46F},
                                               .units = twin::Units::bounding_box,
                                               .transformed = false,
                                               .transform = {1, 0, 0, 1, 0, 0},
                                               .spread = twin::Spread::pad,
                                               .offsets = offsets,
                                               .colours = colours});
        out.rect({.fill = twin::Paint::gradient(vig)}, 0.0F, 0.0F, width, height);
    }
}

hlsl::float3 composite_pixel(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples, hlsl::float2 p,
                             hlsl::float2 frame) noexcept {
    const auto n = static_cast<float>(layers.steps.y);
    hlsl::float3 sum = {0.0F, 0.0F, 0.0F};
    for (const hlsl::float3& c : samples) {
        const hlsl::float3 l = {shaders::composite_level(c.x, n), shaders::composite_level(c.y, n),
                                shaders::composite_level(c.z, n)};
        sum = sum + l;
    }
    const hlsl::float3 mean = shaders::composite_mean(sum, static_cast<std::uint32_t>(samples.size()));
    return shaders::composite_steps(layers, mean, p, frame);
}

} // namespace atlas::passes
