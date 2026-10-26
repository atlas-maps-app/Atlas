// The composite (passes/Composite.hpp).
#include "passes/Composite.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "fx/Glints.hpp"
#include "fx/Mat.hpp"
#include "gen/spirv/frame/composite.hpp"
#include "gen/spirv/frame/composite_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x1.hpp"
#include "gen/spirv/frame/composite_x1_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x1_loc1.hpp"
#include "gen/spirv/frame/composite_x1_loc1_glsl_composite_fs.hpp"
#include "gen/spirv/frame/composite_x2.hpp"
#include "gen/spirv/frame/composite_x2_glsl_composite_fs.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/Frames.hpp"
#include "twin/Twin.hpp"
#include "view/Snap.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <format>
#include <string>

namespace atlas::passes {

namespace {

/// The composite's tables' head: the side, the cloud shadows' first row and their 256 pixels, and the rain's block.
constexpr std::size_t table_head = shaders::COMPOSITE_TABLE_HEAD;
static_assert(shaders::COMPOSITE_RAIN_AT == 258 &&
              shaders::COMPOSITE_RAIN_AT + shaders::COMPOSITE_RAIN_WORDS == table_head);

/// A streak of the rain's tile as the SVG prints it: `M x y v length` ('%.1f' each), stroked '%.2f' wide at opacity
/// '%.2f'.
struct Streak {
    double x;
    double y;
    double length;
    double width;
    double opacity;
};

/// The rain's tile, once (render/amsterdam.py:948; ScreenLayers.kt rainStreaks): random.Random(4) gives each streak in
/// turn its x (0 to 127), y (0 to 211), length (3 to 8), width (.3 to .55) and opacity (.35 to .9).
[[nodiscard]] const std::array<Streak, shaders::COMPOSITE_RAIN_COUNT>& rain_streaks() {
    static const std::array<Streak, shaders::COMPOSITE_RAIN_COUNT> streaks = [] {
        pymath::PyRandom rnd(4);
        std::array<Streak, shaders::COMPOSITE_RAIN_COUNT> out{};
        for (Streak& s : out) {
            const double x = rnd.uniform(0, 127);
            const double y = rnd.uniform(0, 211);
            const double length = rnd.uniform(3, 8);
            const double width = rnd.uniform(0.3, 0.55);
            const double opacity = rnd.uniform(0.35, 0.9);
            s = {.x = view::decimals(x, 1),
                 .y = view::decimals(y, 1),
                 .length = view::decimals(length, 1),
                 .width = view::decimals(width, 2),
                 .opacity = view::decimals(opacity, 2)};
        }
        return out;
    }();
    return streaks;
}

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

    // Set 2: the samples and their stencil, as the subpass (or the local reads) give them, and the frame's tables.
    const vk::raii::Device& d = context.device.handle();
    const std::array inputs{
        gpu::Binding{
            .slot = 0, .type = vk::DescriptorType::eInputAttachment, .stages = vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{
            .slot = 1, .type = vk::DescriptorType::eInputAttachment, .stages = vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = 2,
                     .type = vk::DescriptorType::eStorageBufferDynamic,
                     .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(inputs_layout, gpu::make_set_layout(d, inputs));
    inputs_layout_ = std::move(inputs_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{inputs};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 1));
    pool_ = std::move(pool);
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, inputs_layout_));
    inputs_ = std::move(set);
    // The tables: a slice per frame slot of three tables, each as long as the output's longer side (upright or turned,
    // its rows and columns are within it).
    const vk::Extent2D extent = context.output.extent();
    side_ = std::max({extent.width, extent.height, 1U});
    // the header (the side, the cloud shadows' first row and their 256 pixels), then the three tables
    const vk::DeviceSize table_bytes = (table_head + (vk::DeviceSize{side_} * 3)) * sizeof(std::uint32_t);
    const vk::DeviceSize align =
        std::max<vk::DeviceSize>(context.device.facts().limits.minStorageBufferOffsetAlignment, 256);
    slice_ = (table_bytes + align - 1) / align * align;
    ATLAS_GPU_LET(tables,
                  context.device.memory().buffer(slice_ * gpu::Frames::in_flight,
                                                 vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    tables_ = std::move(tables);
    std::ranges::fill(tables_.bytes(), std::byte{0});
    tables_.flush();
    words_.assign(table_head + (std::size_t{side_} * 3), 0U);
    gpu::SetWriter(*inputs_)
        .image(0, vk::DescriptorType::eInputAttachment, pass.colour_input(), pass.colour_input_layout())
        .image(1, vk::DescriptorType::eInputAttachment, pass.stencil_input(), pass.stencil_input_layout())
        .buffer(2, vk::DescriptorType::eStorageBufferDynamic, tables_.handle(), 0, table_bytes)
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
    tables_ = {};
    words_.clear();
    rain_made_ = false;
    side_ = 0;
    slice_ = 0;
    pipeline_.clear();
    layout_.clear();
    inputs_.clear();
    pool_.clear();
    inputs_layout_.clear();
    glsl_module_.clear();
    module_.clear();
}

void Composite::update(const frame::FrameState& state) {
    if (!tables_ || side_ == 0) return;
    const shaders::FrameLayers& l = state.block.layers;
    const std::uint32_t steps = l.steps.x & shaders::COMPOSITE_DRAWN;
    const float width = state.block.camera.frame.x;
    const float height = state.block.camera.frame.y;
    const float density = state.block.camera.frame.z;
    // The header: the tables' side; wfx's cloud shadows' first row (the first upright device row whose centre lies in
    // their rect, from max(0, horizon) down) and their table, made again when the moment's matrix changes.
    words_[0] = side_;
    words_[1] = 0xFFFF'FFFFU;
    if ((steps & shaders::COMPOSITE_CLOUD_SHADOWS) != 0U && state.layers && state.layers->cloud_shadows()) {
        const double top = std::max(0.0, state.layers->horizon);
        words_[1] = static_cast<std::uint32_t>(std::max(0.0, std::ceil((top * static_cast<double>(density)) - 0.5)));
        const auto cut = static_cast<float>(view::decimals(state.layers->cloud_shadow_cut, 2));
        if (cut != cloud_cut_) {
            cloud_ = fx::cloud_shadow_table(cut);
            cloud_cut_ = cut;
        }
        std::ranges::copy(cloud_, words_.begin() + 2);
    }
    // wfx's rain: its block, made again when the moment's angle or the density changes
    if ((steps & shaders::COMPOSITE_RAIN) != 0U && state.layers && state.layers->rain_angle) {
        const std::int64_t angle = *state.layers->rain_angle;
        const auto d = static_cast<double>(density);
        if (!rain_made_ || angle != rain_angle_ || d != rain_density_) {
            rain_block(angle, d, std::span(words_).subspan(shaders::COMPOSITE_RAIN_AT, shaders::COMPOSITE_RAIN_WORDS));
            rain_angle_ = angle;
            rain_density_ = d;
            rain_made_ = true;
        }
    }
    // Where a step does not run its table is not read (composite_steps_alphas skips the step).
    std::uint32_t* const t = words_.data() + table_head;
    if ((steps & shaders::COMPOSITE_HAZE) != 0U && l.haze.w > 0.0F) {
        for (std::uint32_t row = 0; row < side_; ++row) {
            t[row] = static_cast<std::uint32_t>(shaders::composite_haze_alpha12(l, row, density));
        }
    }
    if ((steps & shaders::COMPOSITE_VIGNETTE) != 0U) {
        const std::uint32_t w16 = shaders::composite_vignette_size16(width, density);
        const std::uint32_t h16 = shaders::composite_vignette_size16(height, density);
        for (std::uint32_t at = 0; at < side_; ++at) {
            t[side_ + at] = shaders::composite_vignette_square(at, w16, 32768);       // .5
            t[(2 * side_) + at] = shaders::composite_vignette_square(at, h16, 30147); // .46: 30146.56
        }
    }
    const std::size_t at = static_cast<std::size_t>(slice_) * (state.slot % gpu::Frames::in_flight);
    std::memcpy(tables_.bytes().subspan(at).data(), words_.data(), words_.size() * sizeof(std::uint32_t));
    tables_.flush();
}

void Composite::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    const auto offset = static_cast<std::uint32_t>(slice_ * (recording.frame.slot.slot % gpu::Frames::in_flight));
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *inputs_, offset);
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
    // render/amsterdam.py:943: wfx's cloud shadows before the haze (ScreenLayers.kt wfx): the `cloudsh` filter over a
    // rect from the horizon down, at .42 by multiply, once the fields are bound
    if ((on & shaders::COMPOSITE_CLOUD_SHADOWS) != 0U && (state.block.info.x & shaders::FRAME_FIELDS) != 0U &&
        state.layers) {
        const std::int32_t clouds = out.filter("cloudsh");
        const auto top = static_cast<float>(std::max(0.0, state.layers->horizon));
        out.rect({.fill = twin::Paint::colour(0xFF00'0000U)}, 0.0F, top, width, height - top, 0.0F, 0.0F,
                 {.opacity = 0.42F, .blend = twin::Blend::multiply, .filter = clouds});
    }
    // render/amsterdam.py:947: wfx's rain over the frame after the cloud shadows (ScreenLayers.kt wfx): the `rain`
    // pattern at .45
    if ((on & shaders::COMPOSITE_RAIN) != 0U) {
        const std::int32_t rain = out.pattern("rain");
        out.rect({.fill = twin::Paint::pattern(rain)}, 0.0F, 0.0F, width, height, 0.0F, 0.0F, {.opacity = 0.45F});
    }
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
    // render/amsterdam.py:921: the golden wash over the haze (ScreenLayers.kt washDef and washRect)
    if ((on & shaders::COMPOSITE_WASH) != 0U) {
        const auto rgb = [](std::uint32_t c) {
            return hlsl::float4{static_cast<float>((c >> 16U) & 0xFFU) / 255.0F,
                                static_cast<float>((c >> 8U) & 0xFFU) / 255.0F, static_cast<float>(c & 0xFFU) / 255.0F,
                                0.0F};
        };
        const std::array<float, 3> offsets{0.0F, 0.45F, 1.0F};
        const std::array<std::uint32_t, 3> colours{folded(rgb(0xFFB25EU), 0.14F), folded(rgb(0xFFC98AU), 0.05F),
                                                   folded(rgb(0xFFC98AU), 0.0F)};
        const std::int32_t wash = out.gradient({.id = "wash",
                                                .radial = false,
                                                .coords = {0.0F, 0.0F, 1.0F, 0.35F, 0.0F},
                                                .units = twin::Units::bounding_box,
                                                .transformed = false,
                                                .transform = {1, 0, 0, 1, 0, 0},
                                                .spread = twin::Spread::pad,
                                                .offsets = offsets,
                                                .colours = colours});
        out.rect({.fill = twin::Paint::gradient(wash)}, 0.0F, 0.0F, width, height);
    }
    // render/amsterdam.py:964: fin's grain over everything (its `grain` filter, overlaid at .09, .12 at night), once
    // the fields are bound
    if ((on & shaders::COMPOSITE_GRAIN) != 0U && (state.block.info.x & shaders::FRAME_FIELDS) != 0U) {
        const bool night = (state.block.info.x & shaders::FRAME_NIGHT) != 0U;
        const std::int32_t grain = out.filter("grain");
        out.rect({.fill = twin::Paint::colour(0xFF00'0000U)}, 0.0F, 0.0F, width, height, 0.0F, 0.0F,
                 {.opacity = night ? 0.12F : 0.09F, .blend = twin::Blend::overlay, .filter = grain});
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

hlsl::float3 composite_pixel_fx(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples,
                                const CompositeInputs& inputs, hlsl::uint2 pixel, hlsl::float3 frame) noexcept {
    const std::uint32_t levels = layers.steps.y;
    const auto n = static_cast<float>(levels);
    // The samples' levels, the objects group's and the others' apart, as the shader sums them.
    hlsl::float3 objects = {0.0F, 0.0F, 0.0F};
    hlsl::float3 others = {0.0F, 0.0F, 0.0F};
    std::uint32_t k = 0;
    for (std::size_t s = 0; s < samples.size(); ++s) {
        const hlsl::float3& c = samples[s];
        const hlsl::float3 l = {shaders::composite_level(c.x, n), shaders::composite_level(c.y, n),
                                shaders::composite_level(c.z, n)};
        if (((inputs.objects >> s) & 1U) != 0U) {
            objects = objects + l;
            ++k;
        } else {
            others = others + l;
        }
    }
    const auto count = static_cast<std::uint32_t>(samples.size());
    hlsl::float3 mean;
    if ((layers.steps.x & shaders::COMPOSITE_MAT) != 0U && k > 0 && k < count && inputs.mat) {
        const hlsl::uint3 o = shaders::composite_mean_of(objects, k);
        const std::span<const std::uint16_t> edge = fx::mat_edge_table(levels);
        const hlsl::uint3 e = {edge[o.x], edge[o.y], edge[o.z]};
        mean = shaders::composite_silhouette(e, shaders::composite_mean_of(others, count - k), k, count);
    } else {
        mean = shaders::composite_mean(objects + others, count);
    }
    return shaders::composite_steps_fx(layers, mean, pixel, frame, inputs.grain, inputs.cloud, inputs.rain);
}

void rain_block(std::int64_t angle, double density, std::span<std::uint32_t> out) {
    std::ranges::fill(out, 0U);
    if (out.size() < shaders::COMPOSITE_RAIN_WORDS) return;
    const auto word = [](std::int64_t v) { return static_cast<std::uint32_t>(static_cast<std::int32_t>(v)); };
    // The transform: rotate(angle) undone over the density, in 1/65536 px a device px (at least .5 device px a CSS px,
    // so every place the shader takes stays within its bias), and the four points' offsets from the pixel's corner (the
    // 4x standard sample positions) turned likewise.
    const double d = std::max(density, 0.5);
    const double radians = pymath::radians(static_cast<double>(angle));
    const double c = pymath::cos(radians) / d;
    const double s = pymath::sin(radians) / d;
    out[0] = word(std::llround(c * 65536.0));
    out[1] = word(std::llround(s * 65536.0));
    constexpr std::array<std::array<double, 2>, 4> points{
        {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};
    for (std::size_t k = 0; k < points.size(); ++k) {
        const double x = points[k][0];
        const double y = points[k][1];
        out[2 + (2 * k)] = word(std::llround(((c * x) + (s * y)) * 65536.0));
        out[3 + (2 * k)] = word(std::llround(((c * y) - (s * x)) * 65536.0));
    }
    // The streaks in 1/256 px, and the cells each reaches within the tile: its segment grown by its half width (the
    // whole number at least the root of r², so every point the shader finds within it lies in a cell that lists it).
    constexpr std::uint32_t gx = shaders::COMPOSITE_RAIN_GRID_X;
    constexpr std::uint32_t gy = shaders::COMPOSITE_RAIN_GRID_Y;
    std::vector<std::vector<std::uint32_t>> cells(std::size_t{gx} * gy);
    const std::array<Streak, shaders::COMPOSITE_RAIN_COUNT>& streaks = rain_streaks();
    for (std::uint32_t i = 0; i < streaks.size(); ++i) {
        const Streak& st = streaks[i];
        const auto x = static_cast<std::int32_t>(std::lround(st.x * 256.0));
        const auto y0 = static_cast<std::int32_t>(std::lround(st.y * 256.0));
        const auto y1 = static_cast<std::int32_t>(std::lround((st.y + st.length) * 256.0));
        const auto r2 = static_cast<std::uint32_t>(std::lround(st.width * st.width * 16384.0));
        const auto opacity = static_cast<std::uint32_t>(std::lround(st.opacity * 100.0));
        const std::size_t at = shaders::COMPOSITE_RAIN_STREAKS + (std::size_t{4} * i);
        out[at] = static_cast<std::uint32_t>(x);
        out[at + 1] = static_cast<std::uint32_t>(y0);
        out[at + 2] = static_cast<std::uint32_t>(y1);
        out[at + 3] = r2 | (opacity << 16U);
        std::uint32_t root = 0;
        while (root * root < r2) ++root;
        const auto reach = static_cast<std::int32_t>(root);
        const auto cell = [](std::int32_t v, std::uint32_t n) {
            return static_cast<std::uint32_t>(std::clamp(v >> 11, 0, static_cast<std::int32_t>(n) - 1));
        };
        for (std::uint32_t cy = cell(y0 - reach, gy); cy <= cell(y1 + reach, gy); ++cy) {
            for (std::uint32_t cx = cell(x - reach, gx); cx <= cell(x + reach, gx); ++cx) {
                cells[(std::size_t{cy} * gx) + cx].push_back(i);
            }
        }
    }
    const std::size_t room = shaders::COMPOSITE_RAIN_WORDS - shaders::COMPOSITE_RAIN_LISTS;
    std::uint32_t next = 0;
    for (std::size_t k = 0; k < cells.size(); ++k) {
        const auto count = static_cast<std::uint32_t>(std::min(cells[k].size(), room - next));
        out[shaders::COMPOSITE_RAIN_CELLS + k] = next | (count << 16U);
        for (std::uint32_t j = 0; j < count; ++j) out[shaders::COMPOSITE_RAIN_LISTS + next++] = cells[k][j];
    }
}

hlsl::float3 composite_pixel(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples, hlsl::float2 p,
                             hlsl::float2 frame) noexcept {
    const hlsl::uint2 pixel = {static_cast<std::uint32_t>(p.x), static_cast<std::uint32_t>(p.y)};
    const hlsl::float3 box = {frame.x, frame.y, 1.0F};
    return composite_pixel_fx(layers, samples, CompositeInputs{}, pixel, box);
}

} // namespace atlas::passes
