// VK bench's stand-in load (standin/StandIn.hpp).
#include "standin/StandIn.hpp"

#include "base/assert.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/standin/blur.hpp"
#include "gen/spirv/standin/composite.hpp"
#include "gen/spirv/standin/composite_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x1.hpp"
#include "gen/spirv/standin/composite_x1_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x1_loc1.hpp"
#include "gen/spirv/standin/composite_x1_loc1_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x2.hpp"
#include "gen/spirv/standin/composite_x2_glsl_composite_fs.hpp"
#include "gen/spirv/standin/frosted.hpp"
#include "gen/spirv/standin/standin.hpp"
#include "gen/spirv/standin/standin_onepass.hpp"
#include "gpu/Sync.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <format>
#include <string>
#include <string_view>

namespace atlas::standin {

namespace {

namespace lay = layouts::standin_standin;
using Push = lay::Push;
using Haze = layouts::standin_composite::Haze;
using BlurPush = layouts::standin_blur::BlurPush;
using FrostPush = layouts::standin_frosted::FrostPush;

// A variant differs from its module by a macro only, so it reads the same uniforms, buffers and push constants.
static_assert(sizeof(layouts::standin_standin_onepass::Frame) == sizeof(lay::Frame));
static_assert(sizeof(layouts::standin_standin_onepass::Push) == sizeof(Push));
static_assert(sizeof(layouts::standin_composite_x2::Haze) == sizeof(Haze));
static_assert(sizeof(layouts::standin_composite_x1::Haze) == sizeof(Haze));

/// An embedded module and its symbol, by which the log and the crash guard name it.
struct Spirv {
    std::string_view name;
    std::span<const std::uint32_t> words;
};

/// The shader module of `spirv`, announced for its making alone (the log, the crash guard), so that a driver crashing
/// in vkCreateShaderModule is known by the module, and nothing made after it by its name.
[[nodiscard]] gpu::Result<vk::raii::ShaderModule> module_of(const gpu::Device& device, const Spirv& spirv,
                                                            gpu::PipelineWatch* watch) {
    const gpu::Announced named = gpu::announce_module(spirv.name, watch);
    return gpu::make_module(device.handle(), spirv.words);
}

/// The composite's modules for the samples it reads (one module per sample count: the core's modules hold no
/// specialization constants), as Slang's own SPIR-V and through its GLSL route. At one sample in the dynamic route
/// (`loc1`), the composite writes colour attachment 1.
struct CompositeModules {
    Spirv direct;
    Spirv glsl;
};

[[nodiscard]] gpu::Result<CompositeModules> composite_modules(vk::SampleCountFlagBits samples, bool loc1) {
    using S = vk::SampleCountFlagBits;
    switch (samples) {
    case S::e4:
        return CompositeModules{.direct = {.name = "standin_composite", .words = spirv::standin_composite},
                                .glsl = {.name = "standin_composite_glsl_composite_fs",
                                         .words = spirv::standin_composite_glsl_composite_fs}};
    case S::e2:
        return CompositeModules{.direct = {.name = "standin_composite_x2", .words = spirv::standin_composite_x2},
                                .glsl = {.name = "standin_composite_x2_glsl_composite_fs",
                                         .words = spirv::standin_composite_x2_glsl_composite_fs}};
    case S::e1:
        if (loc1) {
            return CompositeModules{
                .direct = {.name = "standin_composite_x1_loc1", .words = spirv::standin_composite_x1_loc1},
                .glsl = {.name = "standin_composite_x1_loc1_glsl_composite_fs",
                         .words = spirv::standin_composite_x1_loc1_glsl_composite_fs}};
        }
        return CompositeModules{.direct = {.name = "standin_composite_x1", .words = spirv::standin_composite_x1},
                                .glsl = {.name = "standin_composite_x1_glsl_composite_fs",
                                         .words = spirv::standin_composite_x1_glsl_composite_fs}};
    default:
        return gpu::fail(std::format("the stand-in has no composite module for {} samples", vk::to_string(samples)));
    }
}

constexpr std::uint32_t box_chunks = 256; // the boxes' draws (D5.7: 130-300 a frame)
constexpr std::uint32_t atlas_size = 2048;
constexpr std::uint32_t atlas_per_frame = 64; // crowns redrawn each frame
constexpr std::uint32_t glows = 64;
constexpr double shadow_radius = 1200.0; // the renders' radius cut for the shadows (D9)
constexpr int shadow_blur = 2;           // box radius at half size
constexpr int frost_blur = 6;            // box radius at quarter size (σ ≈ 20 px at full size, as the pills')

using clock = std::chrono::steady_clock;
[[nodiscard]] double ms_since(clock::time_point t) {
    return std::chrono::duration<double, std::milli>(clock::now() - t).count();
}

[[nodiscard]] vk::DeviceSize align_up(vk::DeviceSize v, vk::DeviceSize a) noexcept { return (v + a - 1) / a * a; }

[[nodiscard]] gpu::Result<vk::raii::ImageView> view_of(const gpu::Device& device, const gpu::Image& image,
                                                       vk::ImageAspectFlags aspect) {
    ATLAS_VK_LET(
        view,
        device.handle().createImageView({.image = image.handle(),
                                         .viewType = vk::ImageViewType::e2D,
                                         .format = image.format(),
                                         .subresourceRange = {.aspectMask = aspect, .levelCount = 1, .layerCount = 1}}),
        "a stand-in image's view");
    return std::move(view);
}

[[nodiscard]] gpu::Result<gpu::Image> image_of(const gpu::Device& device, vk::Format format, vk::Extent2D extent,
                                               vk::ImageUsageFlags usage,
                                               vk::SampleCountFlagBits samples = vk::SampleCountFlagBits::e1,
                                               gpu::MemoryUse memory = gpu::MemoryUse::gpu) {
    return device.memory().image({.imageType = vk::ImageType::e2D,
                                  .format = format,
                                  .extent = {.width = extent.width, .height = extent.height, .depth = 1},
                                  .mipLevels = 1,
                                  .arrayLayers = 1,
                                  .samples = samples,
                                  .tiling = vk::ImageTiling::eOptimal,
                                  .usage = usage,
                                  .initialLayout = vk::ImageLayout::eUndefined},
                                 memory);
}

/// A render pass of one subpass: one colour attachment (with a depth attachment and a resolve target when samples > 1).
struct SimplePass {
    vk::Format colour = vk::Format::eUndefined;
    vk::SampleCountFlagBits samples = vk::SampleCountFlagBits::e1;
    vk::Format depth = vk::Format::eUndefined;
    vk::AttachmentLoadOp load = vk::AttachmentLoadOp::eClear;
    vk::ImageLayout initial = vk::ImageLayout::eUndefined;
    vk::ImageLayout final_layout = vk::ImageLayout::eShaderReadOnlyOptimal;
};

[[nodiscard]] gpu::Result<vk::raii::RenderPass> make_pass(const gpu::Device& device, const SimplePass& p) {
    const bool resolve = p.samples != vk::SampleCountFlagBits::e1;
    std::vector<vk::AttachmentDescription> attachments;
    // The target (1x): the colour itself, or the resolve target of the samples.
    attachments.push_back({.format = p.colour,
                           .samples = vk::SampleCountFlagBits::e1,
                           .loadOp = resolve ? vk::AttachmentLoadOp::eDontCare : p.load,
                           .storeOp = vk::AttachmentStoreOp::eStore,
                           .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
                           .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                           .initialLayout = resolve ? vk::ImageLayout::eUndefined : p.initial,
                           .finalLayout = p.final_layout});
    if (resolve) {
        attachments.push_back({.format = p.colour,
                               .samples = p.samples,
                               .loadOp = vk::AttachmentLoadOp::eClear,
                               .storeOp = vk::AttachmentStoreOp::eDontCare,
                               .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
                               .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                               .initialLayout = vk::ImageLayout::eUndefined,
                               .finalLayout = vk::ImageLayout::eColorAttachmentOptimal});
    }
    if (p.depth != vk::Format::eUndefined) {
        attachments.push_back({.format = p.depth,
                               .samples = p.samples,
                               .loadOp = vk::AttachmentLoadOp::eClear,
                               .storeOp = vk::AttachmentStoreOp::eDontCare,
                               .stencilLoadOp = vk::AttachmentLoadOp::eClear,
                               .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                               .initialLayout = vk::ImageLayout::eUndefined,
                               .finalLayout = vk::ImageLayout::eDepthStencilAttachmentOptimal});
    }
    const vk::AttachmentReference target{.attachment = 0, .layout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::AttachmentReference samples{.attachment = 1, .layout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::AttachmentReference depth{.attachment = resolve ? 2U : 1U,
                                        .layout = vk::ImageLayout::eDepthStencilAttachmentOptimal};
    const vk::SubpassDescription subpass{.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                                         .colorAttachmentCount = 1,
                                         .pColorAttachments = resolve ? &samples : &target,
                                         .pResolveAttachments = resolve ? &target : nullptr,
                                         .pDepthStencilAttachment =
                                             p.depth != vk::Format::eUndefined ? &depth : nullptr};
    // In: after the previous frame read these images (and wrote them) and after copies into the target; out: before
    // the fragment and compute shaders that sample the result.
    constexpr vk::PipelineStageFlags tests =
        vk::PipelineStageFlagBits::eEarlyFragmentTests | vk::PipelineStageFlagBits::eLateFragmentTests;
    const std::array<vk::SubpassDependency, 2> dependencies{{
        {.srcSubpass = vk::SubpassExternal,
         .dstSubpass = 0,
         .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput |
                         vk::PipelineStageFlagBits::eFragmentShader | vk::PipelineStageFlagBits::eComputeShader |
                         vk::PipelineStageFlagBits::eTransfer | tests,
         .dstStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput | tests,
         .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eTransferWrite |
                          vk::AccessFlagBits::eDepthStencilAttachmentWrite,
         .dstAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eColorAttachmentRead |
                          vk::AccessFlagBits::eDepthStencilAttachmentWrite |
                          vk::AccessFlagBits::eDepthStencilAttachmentRead},
        {.srcSubpass = 0,
         .dstSubpass = vk::SubpassExternal,
         .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
         .dstStageMask = vk::PipelineStageFlagBits::eFragmentShader | vk::PipelineStageFlagBits::eComputeShader |
                         vk::PipelineStageFlagBits::eColorAttachmentOutput | vk::PipelineStageFlagBits::eTransfer,
         .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
         .dstAccessMask = vk::AccessFlagBits::eShaderRead | vk::AccessFlagBits::eColorAttachmentRead |
                          vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eTransferRead},
    }};
    ATLAS_VK_LET(pass,
                 device.handle().createRenderPass({.attachmentCount = static_cast<std::uint32_t>(attachments.size()),
                                                   .pAttachments = attachments.data(),
                                                   .subpassCount = 1,
                                                   .pSubpasses = &subpass,
                                                   .dependencyCount = static_cast<std::uint32_t>(dependencies.size()),
                                                   .pDependencies = dependencies.data()}),
                 "a stand-in render pass");
    return std::move(pass);
}

[[nodiscard]] gpu::Result<vk::raii::Framebuffer> make_framebuffer(const gpu::Device& device,
                                                                  const vk::raii::RenderPass& pass,
                                                                  std::span<const vk::ImageView> views,
                                                                  vk::Extent2D extent) {
    ATLAS_VK_LET(framebuffer,
                 device.handle().createFramebuffer({.renderPass = *pass,
                                                    .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                                    .pAttachments = views.data(),
                                                    .width = extent.width,
                                                    .height = extent.height,
                                                    .layers = 1}),
                 "a stand-in framebuffer");
    return std::move(framebuffer);
}

void set_viewport(const vk::raii::CommandBuffer& cmd, vk::Extent2D e) {
    cmd.setViewport(0, vk::Viewport{.x = 0,
                                    .y = 0,
                                    .width = static_cast<float>(e.width),
                                    .height = static_cast<float>(e.height),
                                    .minDepth = 0,
                                    .maxDepth = 1});
    cmd.setScissor(0, vk::Rect2D{.offset = {.x = 0, .y = 0}, .extent = e});
}

void push(const vk::raii::CommandBuffer& cmd, vk::PipelineLayout layout, const Push& p) {
    cmd.pushConstants(layout, vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment, 0,
                      vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
}

} // namespace

std::uint32_t quarter_turns_of(vk::SurfaceTransformFlagBitsKHR transform) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    switch (transform) {
    case T::eRotate90:
    case T::eHorizontalMirrorRotate90: return 1;
    case T::eRotate180:
    case T::eHorizontalMirrorRotate180: return 2;
    case T::eRotate270:
    case T::eHorizontalMirrorRotate270: return 3;
    default: return 0;
    }
}

StandIn::StandIn(const gpu::Device& device, const gpu::PipelineCache& cache, std::shared_ptr<const City> city,
                 gpu::PipelineWatch* watch)
    : device_(device), cache_(cache), city_(std::move(city)), watch_(watch) {}

StandIn::~StandIn() = default;

gpu::Result<std::unique_ptr<StandIn>> StandIn::create(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                      std::shared_ptr<const City> city, gpu::PipelineWatch* watch) {
    std::unique_ptr<StandIn> self(
        new StandIn(device, cache, std::move(city), watch)); // NOLINT(cppcoreguidelines-owning-memory)
    self->ranks_ = std::make_unique<Ranks>(*self->city_);
    ATLAS_GPU_CHECK(self->create_fixed());
    return self;
}

gpu::Result<void> StandIn::make_pipeline(const gpu::GraphicsDesc& desc, vk::raii::Pipeline& out, double& ms,
                                         const gpu::PipelineCache& cache) {
    // Named for the pipeline's making alone; its time is the driver's, not the guard's writes.
    auto made = [&] {
        const gpu::Announced named = gpu::announce(desc.label, watch_);
        const auto started = clock::now();
        auto pipeline = gpu::make_graphics(device_.handle(), cache.handle(), desc);
        ms += ms_since(started);
        return pipeline;
    }();
    ATLAS_GPU_LET(pipeline, std::move(made));
    out = std::move(pipeline);
    return {};
}

vk::DeviceSize StandIn::bytes() const noexcept {
    vk::DeviceSize n = boxes_.size() + trees_.size() + ring_.size() + atlas_.bytes();
    for (const gpu::Image* i :
         {&mask_, &blur_a_, &blur_b_, &frost_c_, &frost_d_, &water_, &water_colour_, &water_depth_})
        n += i->bytes();
    return n;
}

gpu::Result<void> StandIn::create_fixed() {
    const vk::raii::Device& d = device_.handle();
    using T = vk::DescriptorType;
    using S = vk::ShaderStageFlagBits;
    const vk::ShaderStageFlags graphics = S::eVertex | S::eFragment;
    const std::array frame_bindings{gpu::Binding{.slot = 0, .type = T::eUniformBufferDynamic, .stages = graphics},
                                    gpu::Binding{.slot = 1, .type = T::eStorageBufferDynamic, .stages = graphics},
                                    gpu::Binding{.slot = 2, .type = T::eStorageBufferDynamic, .stages = graphics},
                                    gpu::Binding{.slot = 3, .type = T::eStorageBufferDynamic, .stages = graphics}};
    const std::array scene_bindings{gpu::Binding{.slot = 0, .type = T::eStorageBuffer, .stages = graphics},
                                    gpu::Binding{.slot = 1, .type = T::eStorageBuffer, .stages = graphics},
                                    gpu::Binding{.slot = 2, .type = T::eCombinedImageSampler, .stages = graphics},
                                    gpu::Binding{.slot = 3, .type = T::eCombinedImageSampler, .stages = graphics},
                                    gpu::Binding{.slot = 4, .type = T::eCombinedImageSampler, .stages = graphics}};
    const std::array haze_bindings{gpu::Binding{.slot = 0, .type = T::eUniformBufferDynamic, .stages = S::eFragment}};
    const std::array input_bindings{gpu::Binding{.slot = 0, .type = T::eInputAttachment, .stages = S::eFragment},
                                    gpu::Binding{.slot = 1, .type = T::eInputAttachment, .stages = S::eFragment}};
    const std::array blur_bindings{gpu::Binding{.slot = 0, .type = T::eSampledImage, .stages = S::eCompute},
                                   gpu::Binding{.slot = 1, .type = T::eStorageImage, .stages = S::eCompute}};
    const std::array frost_bindings{gpu::Binding{.slot = 0, .type = T::eCombinedImageSampler, .stages = S::eFragment}};
    ATLAS_GPU_LET(frame_layout, gpu::make_set_layout(d, frame_bindings));
    ATLAS_GPU_LET(scene_layout, gpu::make_set_layout(d, scene_bindings));
    ATLAS_GPU_LET(haze_layout, gpu::make_set_layout(d, haze_bindings));
    ATLAS_GPU_LET(inputs_layout, gpu::make_set_layout(d, input_bindings));
    ATLAS_GPU_LET(blur_layout, gpu::make_set_layout(d, blur_bindings));
    ATLAS_GPU_LET(frost_layout, gpu::make_set_layout(d, frost_bindings));
    frame_layout_ = std::move(frame_layout);
    scene_layout_ = std::move(scene_layout);
    haze_layout_ = std::move(haze_layout);
    inputs_layout_ = std::move(inputs_layout);
    blur_layout_ = std::move(blur_layout);
    frost_layout_ = std::move(frost_layout);

    const std::array<vk::DescriptorSetLayout, 2> scene_sets{*frame_layout_, *scene_layout_};
    ATLAS_GPU_LET(scene_pipeline_layout, gpu::make_layout(d, scene_sets, sizeof(Push), graphics));
    const std::array<vk::DescriptorSetLayout, 2> composite_sets{*haze_layout_, *inputs_layout_};
    ATLAS_GPU_LET(composite_pipeline_layout, gpu::make_layout(d, composite_sets, 0, {}));
    const std::array<vk::DescriptorSetLayout, 1> blur_sets{*blur_layout_};
    ATLAS_GPU_LET(blur_pipeline_layout, gpu::make_layout(d, blur_sets, sizeof(BlurPush), S::eCompute));
    const std::array<vk::DescriptorSetLayout, 1> frost_sets{*frost_layout_};
    ATLAS_GPU_LET(frost_pipeline_layout, gpu::make_layout(d, frost_sets, sizeof(FrostPush), graphics));
    scene_pipeline_layout_ = std::move(scene_pipeline_layout);
    composite_pipeline_layout_ = std::move(composite_pipeline_layout);
    blur_pipeline_layout_ = std::move(blur_pipeline_layout);
    frost_pipeline_layout_ = std::move(frost_pipeline_layout);

    // One of each set, five blur sets (the shadow chain's three, the frosted chain's two).
    const std::array<std::span<const gpu::Binding>, 6> all{frame_bindings, scene_bindings, haze_bindings,
                                                           input_bindings, blur_bindings,  frost_bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, all, 5));
    pool_ = std::move(pool);
    ATLAS_GPU_LET(frame_set, gpu::allocate_set(d, pool_, frame_layout_));
    ATLAS_GPU_LET(scene_set, gpu::allocate_set(d, pool_, scene_layout_));
    ATLAS_GPU_LET(haze_set, gpu::allocate_set(d, pool_, haze_layout_));
    ATLAS_GPU_LET(inputs_set, gpu::allocate_set(d, pool_, inputs_layout_));
    ATLAS_GPU_LET(frost_set, gpu::allocate_set(d, pool_, frost_layout_));
    frame_set_ = std::move(frame_set);
    scene_set_ = std::move(scene_set);
    haze_set_ = std::move(haze_set);
    inputs_set_ = std::move(inputs_set);
    frost_set_ = std::move(frost_set);
    for (int i = 0; i < 5; ++i) {
        ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, blur_layout_));
        blur_sets_.push_back(std::move(set));
    }

    ATLAS_VK_LET(sampler,
                 d.createSampler({.magFilter = vk::Filter::eLinear,
                                  .minFilter = vk::Filter::eLinear,
                                  .mipmapMode = vk::SamplerMipmapMode::eNearest,
                                  .addressModeU = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeV = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeW = vk::SamplerAddressMode::eClampToEdge,
                                  .maxLod = 0.0F}),
                 "the stand-in's sampler");
    sampler_ = std::move(sampler);

    // The city, written once into host-visible memory (device-local on unified-memory phones).
    const City& city = *city_;
    ATLAS_GPU_LET(boxes, device_.memory().buffer(city.boxes.size() * sizeof(Box),
                                                 vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    ATLAS_GPU_LET(trees, device_.memory().buffer(city.trees.size() * sizeof(Tree),
                                                 vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    std::memcpy(boxes.bytes().data(), city.boxes.data(), city.boxes.size() * sizeof(Box));
    std::memcpy(trees.bytes().data(), city.trees.data(), city.trees.size() * sizeof(Tree));
    boxes.flush();
    trees.flush();
    boxes_ = std::move(boxes);
    trees_ = std::move(trees);

    // The per-frame ring: each frame in flight has its own uniforms, ranks, draw order and casters.
    const vk::PhysicalDeviceLimits& limits = device_.facts().limits;
    const auto align =
        std::max<vk::DeviceSize>({limits.minUniformBufferOffsetAlignment, limits.minStorageBufferOffsetAlignment, 256});
    offsets_.frame = 0;
    offsets_.haze = align_up(sizeof(FrameUniforms), align);
    offsets_.ranks = offsets_.haze + align_up(sizeof(Haze), align);
    offsets_.order = offsets_.ranks + align_up(city.items() * 4ULL, align);
    offsets_.casters = offsets_.order + align_up(city.boxes.size() * 4ULL, align);
    slot_bytes_ = offsets_.casters + align_up((city.boxes.size() + city.waterside.size()) * 4ULL, align);
    ATLAS_GPU_LET(
        ring, device_.memory().buffer(slot_bytes_ * gpu::Frames::in_flight,
                                      vk::BufferUsageFlagBits::eUniformBuffer | vk::BufferUsageFlagBits::eStorageBuffer,
                                      gpu::MemoryUse::upload));
    ring_ = std::move(ring);
    gpu::SetWriter(*frame_set_)
        .buffer(0, T::eUniformBufferDynamic, ring_.handle(), offsets_.frame, sizeof(FrameUniforms))
        .buffer(1, T::eStorageBufferDynamic, ring_.handle(), offsets_.ranks, city.items() * 4ULL)
        .buffer(2, T::eStorageBufferDynamic, ring_.handle(), offsets_.order, city.boxes.size() * 4ULL)
        .buffer(3, T::eStorageBufferDynamic, ring_.handle(), offsets_.casters,
                (city.boxes.size() + city.waterside.size()) * 4ULL)
        .apply(d);
    gpu::SetWriter(*haze_set_)
        .buffer(0, T::eUniformBufferDynamic, ring_.handle(), offsets_.haze, sizeof(Haze))
        .apply(d);

    // The shaders.
    ATLAS_GPU_LET(standin, module_of(device_, {.name = "standin_standin", .words = spirv::standin_standin}, watch_));
    ATLAS_GPU_LET(
        standin_onepass,
        module_of(device_, {.name = "standin_standin_onepass", .words = spirv::standin_standin_onepass}, watch_));
    ATLAS_GPU_LET(blur_module, module_of(device_, {.name = "standin_blur", .words = spirv::standin_blur}, watch_));
    ATLAS_GPU_LET(frost_module,
                  module_of(device_, {.name = "standin_frosted", .words = spirv::standin_frosted}, watch_));
    standin_module_ = std::move(standin);
    standin_onepass_module_ = std::move(standin_onepass);
    blur_module_ = std::move(blur_module);
    frost_module_ = std::move(frost_module);

    // The atlas: 2048² of crowns, drawn into a few at a time and kept between frames.
    ATLAS_GPU_LET(atlas, image_of(device_, vk::Format::eR8G8B8A8Unorm, {atlas_size, atlas_size},
                                  vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eSampled));
    atlas_ = std::move(atlas);
    ATLAS_GPU_LET(atlas_view, view_of(device_, atlas_, vk::ImageAspectFlagBits::eColor));
    atlas_view_ = std::move(atlas_view);
    ATLAS_GPU_LET(clear_pass, make_pass(device_, {.colour = vk::Format::eR8G8B8A8Unorm}));
    ATLAS_GPU_LET(load_pass, make_pass(device_, {.colour = vk::Format::eR8G8B8A8Unorm,
                                                 .load = vk::AttachmentLoadOp::eLoad,
                                                 .initial = vk::ImageLayout::eShaderReadOnlyOptimal}));
    atlas_clear_pass_ = std::move(clear_pass);
    atlas_load_pass_ = std::move(load_pass);
    const std::array<vk::ImageView, 1> atlas_views{*atlas_view_};
    ATLAS_GPU_LET(atlas_framebuffer, make_framebuffer(device_, atlas_clear_pass_, atlas_views,
                                                      {.width = atlas_size, .height = atlas_size}));
    atlas_framebuffer_ = std::move(atlas_framebuffer);
    ATLAS_GPU_LET(shadow_pass, make_pass(device_, {.colour = vk::Format::eR8Unorm}));
    shadow_pass_ = std::move(shadow_pass);

    // The pipelines every configuration shares, made once.
    fixed_pipelines_ms_ = 0;
    gpu::GraphicsDesc atlas_desc{.label = {.name = "atlas", .module = "standin_standin"},
                                 .module = *standin_module_,
                                 .vertex = "atlas_vs",
                                 .fragment = "atlas_fs",
                                 .layout = *scene_pipeline_layout_};
    atlas_desc.target = {.render_pass = *atlas_clear_pass_, .subpass = 0, .colour_count = 1};
    ATLAS_GPU_CHECK(make_pipeline(atlas_desc, atlas_pipeline_, fixed_pipelines_ms_, cache_));
    gpu::GraphicsDesc shadow_desc{.label = {.name = "shadow", .module = "standin_standin"},
                                  .module = *standin_module_,
                                  .vertex = "shadow_vs",
                                  .fragment = "shadow_fs",
                                  .layout = *scene_pipeline_layout_};
    shadow_desc.target = {.render_pass = *shadow_pass_, .subpass = 0, .colour_count = 1};
    ATLAS_GPU_CHECK(make_pipeline(shadow_desc, shadow_pipeline_, fixed_pipelines_ms_, cache_));
    const gpu::PipelineLabel blur_label{.name = "blur", .module = "standin_blur"};
    auto blur_made = [&] {
        const gpu::Announced named = gpu::announce(blur_label, watch_);
        const auto started = clock::now();
        auto pipeline =
            gpu::make_compute(d, cache_.handle(), blur_label, *blur_module_, "blur_cs", *blur_pipeline_layout_);
        fixed_pipelines_ms_ += ms_since(started);
        return pipeline;
    }();
    ATLAS_GPU_LET(blur_pipeline, std::move(blur_made));
    blur_pipeline_ = std::move(blur_pipeline);
    pipelines_ms_ = fixed_pipelines_ms_;
    pipeline_count_ = 3;
    return {};
}

gpu::Result<void> StandIn::prepare_offscreen(const gpu::MainPass& pass, const gpu::Output& output) {
    const vk::raii::Device& d = device_.handle();
    const vk::Extent2D e = output.extent();
    const vk::SampleCountFlagBits samples = pass.spec().config.samples();
    extent_ = e;
    half_ = {.width = std::max(1U, e.width / 2), .height = std::max(1U, e.height / 2)};
    quarter_ = {.width = std::max(1U, e.width / 4), .height = std::max(1U, e.height / 4)};
    water_extent_ = {.width = std::max(1U, static_cast<std::uint32_t>(e.width * 0.84)),
                     .height = std::max(1U, static_cast<std::uint32_t>(e.height * 0.36))};
    using U = vk::ImageUsageFlagBits;
    ATLAS_GPU_LET(mask, image_of(device_, vk::Format::eR8Unorm, half_, U::eColorAttachment | U::eSampled));
    ATLAS_GPU_LET(blur_a, image_of(device_, vk::Format::eR8G8B8A8Unorm, half_, U::eStorage | U::eSampled));
    ATLAS_GPU_LET(blur_b, image_of(device_, vk::Format::eR8G8B8A8Unorm, half_, U::eStorage | U::eSampled));
    ATLAS_GPU_LET(frost_c,
                  image_of(device_, vk::Format::eR8G8B8A8Unorm, quarter_, U::eTransferDst | U::eStorage | U::eSampled));
    ATLAS_GPU_LET(frost_d, image_of(device_, vk::Format::eR8G8B8A8Unorm, quarter_, U::eStorage | U::eSampled));
    ATLAS_GPU_LET(water,
                  image_of(device_, vk::Format::eR8G8B8A8Unorm, water_extent_, U::eColorAttachment | U::eSampled));
    mask_ = std::move(mask);
    blur_a_ = std::move(blur_a);
    blur_b_ = std::move(blur_b);
    frost_c_ = std::move(frost_c);
    frost_d_ = std::move(frost_d);
    water_ = std::move(water);
    const gpu::MemoryUse transient = pass.spec().config.lazy() ? gpu::MemoryUse::lazy : gpu::MemoryUse::gpu;
    const vk::Format depth_format = device_.facts().depth_stencil;
    if (samples != vk::SampleCountFlagBits::e1) {
        ATLAS_GPU_LET(colour, image_of(device_, vk::Format::eR8G8B8A8Unorm, water_extent_,
                                       U::eColorAttachment | U::eTransientAttachment, samples, transient));
        water_colour_ = std::move(colour);
    } else {
        water_colour_ = {};
    }
    ATLAS_GPU_LET(depth, image_of(device_, depth_format, water_extent_,
                                  U::eDepthStencilAttachment | U::eTransientAttachment, samples, transient));
    water_depth_ = std::move(depth);
    ATLAS_GPU_LET(mask_view, view_of(device_, mask_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(a_view, view_of(device_, blur_a_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(b_view, view_of(device_, blur_b_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(c_view, view_of(device_, frost_c_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(d_view, view_of(device_, frost_d_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(water_view, view_of(device_, water_, vk::ImageAspectFlagBits::eColor));
    ATLAS_GPU_LET(water_depth_view,
                  view_of(device_, water_depth_, vk::ImageAspectFlagBits::eDepth | vk::ImageAspectFlagBits::eStencil));
    mask_view_ = std::move(mask_view);
    blur_a_view_ = std::move(a_view);
    blur_b_view_ = std::move(b_view);
    frost_c_view_ = std::move(c_view);
    frost_d_view_ = std::move(d_view);
    water_view_ = std::move(water_view);
    water_depth_view_ = std::move(water_depth_view);
    if (water_colour_) {
        ATLAS_GPU_LET(colour_view, view_of(device_, water_colour_, vk::ImageAspectFlagBits::eColor));
        water_colour_view_ = std::move(colour_view);
    }

    const std::array<vk::ImageView, 1> mask_views{*mask_view_};
    ATLAS_GPU_LET(shadow_framebuffer, make_framebuffer(device_, shadow_pass_, mask_views, half_));
    shadow_framebuffer_ = std::move(shadow_framebuffer);
    ATLAS_GPU_LET(
        water_pass,
        make_pass(device_, {.colour = vk::Format::eR8G8B8A8Unorm, .samples = samples, .depth = depth_format}));
    water_pass_ = std::move(water_pass);
    std::vector<vk::ImageView> water_views{*water_view_};
    if (*water_colour_view_) water_views.push_back(*water_colour_view_);
    water_views.push_back(*water_depth_view_);
    ATLAS_GPU_LET(water_framebuffer, make_framebuffer(device_, water_pass_, water_views, water_extent_));
    water_framebuffer_ = std::move(water_framebuffer);

    // The frosted pills draw over the frame, loading it; one framebuffer per output image.
    output_final_ = output.final_layout();
    ATLAS_GPU_LET(frost_pass, make_pass(device_, {.colour = output.format(),
                                                  .load = vk::AttachmentLoadOp::eLoad,
                                                  .initial = vk::ImageLayout::eColorAttachmentOptimal,
                                                  .final_layout = output_final_}));
    frost_pass_ = std::move(frost_pass);
    for (const vk::ImageView v : output.views()) {
        const std::array<vk::ImageView, 1> views{v};
        ATLAS_GPU_LET(framebuffer, make_framebuffer(device_, frost_pass_, views, e));
        frost_framebuffers_.push_back(std::move(framebuffer));
    }
    frost_ready_ = output.transfer_source();
    quarter_turns_ = quarter_turns_of(output.transform());

    // The sets that point at the new images.
    using T = vk::DescriptorType;
    gpu::SetWriter(*scene_set_)
        .buffer(0, T::eStorageBuffer, boxes_.handle())
        .buffer(1, T::eStorageBuffer, trees_.handle())
        .image(2, T::eCombinedImageSampler, *blur_b_view_, vk::ImageLayout::eShaderReadOnlyOptimal, *sampler_)
        .image(3, T::eCombinedImageSampler, *water_view_, vk::ImageLayout::eShaderReadOnlyOptimal, *sampler_)
        .image(4, T::eCombinedImageSampler, *atlas_view_, vk::ImageLayout::eShaderReadOnlyOptimal, *sampler_)
        .apply(d);
    auto chain = [&](std::size_t set, vk::ImageView from, vk::ImageLayout from_layout, vk::ImageView to) {
        gpu::SetWriter(*blur_sets_[set])
            .image(0, T::eSampledImage, from, from_layout)
            .image(1, T::eStorageImage, to, vk::ImageLayout::eGeneral)
            .apply(d);
    };
    chain(0, *mask_view_, vk::ImageLayout::eShaderReadOnlyOptimal, *blur_a_view_);
    chain(1, *blur_a_view_, vk::ImageLayout::eGeneral, *blur_b_view_);
    chain(2, *blur_b_view_, vk::ImageLayout::eGeneral, *blur_a_view_);
    chain(3, *frost_c_view_, vk::ImageLayout::eGeneral, *frost_d_view_);
    chain(4, *frost_d_view_, vk::ImageLayout::eGeneral, *frost_c_view_);
    gpu::SetWriter(*frost_set_)
        .image(0, T::eCombinedImageSampler, *frost_c_view_, vk::ImageLayout::eShaderReadOnlyOptimal, *sampler_)
        .apply(d);
    if (pass.composite()) {
        gpu::SetWriter(*inputs_set_)
            .image(0, T::eInputAttachment, pass.colour_input(), pass.colour_input_layout())
            .image(1, T::eInputAttachment, pass.stencil_input(), pass.stencil_input_layout())
            .apply(d);
    }
    return {};
}

gpu::Result<void> StandIn::prepare_pipelines(const gpu::MainPass& pass, const gpu::Output& output) {
    const gpu::Configuration& c = pass.spec().config;
    // As the log and the crash guard name it, with the frame's colour: a driver compiles a pipeline for its render
    // pass's formats, so a crash in a 10-bit frame's is told from one in an 8-bit frame's.
    const std::string configuration =
        std::format("{} {}", gpu::name_of(c), gpu::colour_name(pass.spec().colour_format));
    double ms = 0;
    std::uint32_t made = 0;
    // The scene's module for the frame's form: in one subpass its fragments apply the screen effects themselves.
    const bool onepass = c.form == gpu::FrameForm::onepass;
    const vk::ShaderModule scene_module = onepass ? *standin_onepass_module_ : *standin_module_;
    const std::string_view scene_name = onepass ? "standin_standin_onepass" : "standin_standin";

    // The objects: opaque, rank depth, the objects bit (stencil bit 7) on every sample they cover.
    const vk::StencilOpState objects_bit{.failOp = vk::StencilOp::eKeep,
                                         .passOp = vk::StencilOp::eReplace,
                                         .depthFailOp = vk::StencilOp::eKeep,
                                         .compareOp = vk::CompareOp::eAlways,
                                         .compareMask = 0x80,
                                         .writeMask = 0x80,
                                         .reference = 0x80};
    gpu::PipelineTarget scene = pass.scene_target();
    std::array<gpu::Blend, 4> scene_blends{};
    if (scene.colour_count == 2)
        scene_blends[1] = gpu::Blend::untouched(); // the 1x dynamic route's output, written by the composite only
    // Through the cache prepare is given (VK bench's colour stage's own), else the stand-in's.
    const gpu::PipelineCache& cache = prepare_cache_ != nullptr ? *prepare_cache_ : cache_;
    auto make = [&](const gpu::GraphicsDesc& desc, vk::raii::Pipeline& out) -> gpu::Result<void> {
        ATLAS_GPU_CHECK(make_pipeline(desc, out, ms, cache));
        ++made;
        return {};
    };
    gpu::GraphicsDesc box{.label = {.name = "box", .module = scene_name, .configuration = configuration},
                          .module = scene_module,
                          .vertex = "box_vs",
                          .fragment = "box_fs",
                          .layout = *scene_pipeline_layout_,
                          .target = scene,
                          .cull = vk::CullModeFlagBits::eBack,
                          .front = vk::FrontFace::eCounterClockwise,
                          .depth_test = true,
                          .depth_write = true,
                          .depth_op = vk::CompareOp::eLess,
                          .stencil_test = true,
                          .stencil = objects_bit,
                          .blends = scene_blends};
    ATLAS_GPU_CHECK(make(box, box_pipeline_));
    gpu::GraphicsDesc tree = box;
    tree.label.name = "tree";
    tree.vertex = "tree_vs";
    tree.fragment = "tree_fs";
    tree.cull = vk::CullModeFlagBits::eNone;
    tree.alpha_to_coverage = true;
    ATLAS_GPU_CHECK(make(tree, tree_pipeline_));
    gpu::GraphicsDesc ground = box;
    ground.label.name = "ground";
    ground.vertex = "ground_vs";
    ground.fragment = "ground_fs";
    ground.cull = vk::CullModeFlagBits::eNone;
    ground.stencil_test = false;
    ATLAS_GPU_CHECK(make(ground, ground_pipeline_));
    gpu::GraphicsDesc glow = ground;
    glow.label.name = "glow";
    glow.vertex = "glow_vs";
    glow.fragment = "glow_fs";
    glow.depth_write = false;
    glow.depth_op = vk::CompareOp::eLessOrEqual;
    glow.blends[0] = gpu::Blend::screen();
    ATLAS_GPU_CHECK(make(glow, glow_pipeline_));

    // The composite: the module for the samples it reads, through Slang's own SPIR-V or its GLSL route.
    if (pass.composite()) {
        const bool loc1 = c.samples() == vk::SampleCountFlagBits::e1 && c.route == gpu::Route::dynamic;
        ATLAS_GPU_LET(modules, composite_modules(c.samples(), loc1));
        composite_glsl_ = !device_.facts().takes_read_without_format();
        ATLAS_GPU_LET(module, module_of(device_, modules.direct, watch_));
        composite_module_ = std::move(module);
        vk::raii::ShaderModule glsl_module{nullptr};
        if (composite_glsl_) {
            ATLAS_GPU_LET(m, module_of(device_, modules.glsl, watch_));
            glsl_module = std::move(m);
        }
        const std::string module_names = composite_glsl_
                                             ? std::format("{} and {}", modules.direct.name, modules.glsl.name)
                                             : std::string(modules.direct.name);
        gpu::GraphicsDesc composite{
            .label = {.name = "composite", .module = module_names, .configuration = configuration},
            .module = *composite_module_,
            .vertex = "fullscreen_vs",
            .fragment = composite_glsl_ ? "main" : "composite_fs",
            .fragment_module = *glsl_module,
            .layout = *composite_pipeline_layout_,
            .target = pass.composite_target()};
        if (composite.target.colour_count == 2) {
            composite.blends[0] = gpu::Blend::untouched();
            composite.blends[1] = gpu::Blend::opaque();
        }
        ATLAS_GPU_CHECK(make(composite, composite_pipeline_));
    }

    // The water's mirrored boxes, at the configuration's samples; mirrored, so their faces turn the other way.
    gpu::GraphicsDesc mirror{.label = {.name = "mirror", .module = scene_name, .configuration = configuration},
                             .module = scene_module,
                             .vertex = "box_mirror_vs",
                             .fragment = "box_fs",
                             .layout = *scene_pipeline_layout_,
                             .cull = vk::CullModeFlagBits::eBack,
                             .front = vk::FrontFace::eClockwise,
                             .depth_test = true,
                             .depth_write = true,
                             .depth_op = vk::CompareOp::eLess};
    mirror.target = {.render_pass = *water_pass_, .subpass = 0, .samples = c.samples(), .colour_count = 1};
    ATLAS_GPU_CHECK(make(mirror, mirror_pipeline_));
    gpu::GraphicsDesc frost{.label = {.name = "frosted", .module = "standin_frosted", .configuration = configuration},
                            .module = *frost_module_,
                            .vertex = "frosted_vs",
                            .fragment = "frosted_fs",
                            .layout = *frost_pipeline_layout_};
    frost.target = {.render_pass = *frost_pass_, .subpass = 0, .colour_count = 1};
    frost.blends[0] = gpu::Blend::over();
    ATLAS_GPU_CHECK(make(frost, frost_pipeline_));
    (void)output;
    pipelines_ms_ = fixed_pipelines_ms_ + ms;
    pipeline_count_ = 3 + made;
    return {};
}

gpu::Result<void> StandIn::prepare(const gpu::MainPass& pass, const gpu::Output& output) {
    release(); // the frames that used them have finished (Renderer drains before it remakes)
    ATLAS_GPU_CHECK(prepare_offscreen(pass, output));
    return prepare_pipelines(pass, output);
}

void StandIn::release() {
    // The framebuffers first (they hold the views), then the pipelines and the composite's module, the passes, the
    // views and the images.
    frost_framebuffers_.clear();
    shadow_framebuffer_.clear();
    water_framebuffer_.clear();
    for (vk::raii::Pipeline* p : {&box_pipeline_, &tree_pipeline_, &ground_pipeline_, &glow_pipeline_,
                                  &composite_pipeline_, &mirror_pipeline_, &frost_pipeline_})
        p->clear();
    composite_module_.clear();
    frost_pass_.clear();
    water_pass_.clear();
    for (vk::raii::ImageView* v : {&mask_view_, &blur_a_view_, &blur_b_view_, &frost_c_view_, &frost_d_view_,
                                   &water_view_, &water_colour_view_, &water_depth_view_})
        v->clear();
    for (gpu::Image* i : {&mask_, &blur_a_, &blur_b_, &frost_c_, &frost_d_, &water_, &water_colour_, &water_depth_})
        *i = {};
    frost_ready_ = false;
    output_final_ = vk::ImageLayout::eUndefined;
}

bool StandIn::holds_prepared() const noexcept {
    const bool pipelines =
        std::ranges::any_of(std::array{&box_pipeline_, &tree_pipeline_, &ground_pipeline_, &glow_pipeline_,
                                       &composite_pipeline_, &mirror_pipeline_, &frost_pipeline_},
                            [](const vk::raii::Pipeline* p) { return static_cast<bool>(**p); });
    const bool views =
        std::ranges::any_of(std::array{&mask_view_, &blur_a_view_, &blur_b_view_, &frost_c_view_, &frost_d_view_,
                                       &water_view_, &water_colour_view_, &water_depth_view_},
                            [](const vk::raii::ImageView* v) { return static_cast<bool>(**v); });
    const bool images = std::ranges::any_of(
        std::array{&mask_, &blur_a_, &blur_b_, &frost_c_, &frost_d_, &water_, &water_colour_, &water_depth_},
        [](const gpu::Image* i) { return static_cast<bool>(*i); });
    return pipelines || views || images || *composite_module_ || *frost_pass_ || *water_pass_ ||
           !frost_framebuffers_.empty() || *shadow_framebuffer_ || *water_framebuffer_;
}

void StandIn::write_frame(std::uint32_t slot) {
    const auto started = clock::now();
    const City& city = *city_;
    const FrameUniforms u = uniforms_of(camera_, city, extent_.width, extent_.height, quarter_turns_, seconds_);
    const ViewBasis v = basis_of(camera_);
    counters_.swaps = ranks_->update(v.eye, v.forward);
    within(city, v.target[0], v.target[2], shadow_radius, casters_);
    shadow_casters_ = static_cast<std::uint32_t>(casters_.size());
    casters_.insert(casters_.end(), city.waterside.begin(), city.waterside.end());
    std::byte* base = ring_.bytes().data() + slot * slot_bytes_;
    std::memcpy(base + offsets_.frame, &u, sizeof u);
    Haze haze{};
    haze.screen = u.screen;
    haze.vignette = u.vignette;
    haze.haze_colour = u.haze_colour;
    haze.haze_stop = u.haze_stop;
    std::memcpy(base + offsets_.haze, &haze, sizeof haze);
    const auto ranks = ranks_->ranks();
    std::memcpy(base + offsets_.ranks, ranks.data(), ranks.size_bytes());
    const auto front = ranks_->front_to_back();
    std::memcpy(base + offsets_.order, front.data(), front.size_bytes());
    std::memcpy(base + offsets_.casters, casters_.data(), casters_.size() * sizeof(std::uint32_t));
    ring_.flush();
    counters_.ranks_ms = ms_since(started);
}

void StandIn::blur(const vk::raii::CommandBuffer& cmd, std::span<const vk::DescriptorSet> sets, vk::Extent2D size,
                   int radius, vk::Image first_target, vk::Image second_target) {
    // Three passes along x, three along y, ping-ponging between the two images; the sets name each pass's source and
    // target in turn (the first set reads the original, the others alternate).
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *blur_pipeline_);
    const gpu::Sync sync(device_.enabled().synchronization2);
    for (int pass = 0; pass < 6; ++pass) {
        const std::size_t set = pass == 0 ? 0 : static_cast<std::size_t>(1 + (pass - 1) % 2);
        const vk::Image target = (pass % 2 == 0) ? first_target : second_target;
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *blur_pipeline_layout_, 0,
                               sets[std::min(set, sets.size() - 1)], {});
        const BlurPush p{.size = {static_cast<std::int32_t>(size.width), static_cast<std::int32_t>(size.height)},
                         .step = pass < 3 ? std::array<std::int32_t, 2>{1, 0} : std::array<std::int32_t, 2>{0, 1},
                         .radius = radius,
                         .pad0 = 0,
                         .pad1 = 0,
                         .pad2 = 0};
        cmd.pushConstants(*blur_pipeline_layout_, vk::ShaderStageFlagBits::eCompute, 0,
                          vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
        cmd.dispatch((size.width + 7) / 8, (size.height + 7) / 8, 1);
        ++counters_.dispatches;
        // The pass's writes, before the next pass reads them (and before it writes the image this pass read).
        sync.barrier(cmd, gpu::ImageDependency{.image = target,
                                               .src_stage = vk::PipelineStageFlagBits2::eComputeShader,
                                               .src_access = vk::AccessFlagBits2::eShaderWrite,
                                               .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
                                               .dst_access =
                                                   vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
                                               .old_layout = vk::ImageLayout::eGeneral,
                                               .new_layout = vk::ImageLayout::eGeneral});
    }
}

void StandIn::record_offscreen(const gpu::FrameContext& frame, std::uint32_t slot) {
    const vk::raii::CommandBuffer& cmd = frame.cmd;
    const gpu::Sync& sync = frame.sync;
    const City& city = *city_;
    const std::array<std::uint32_t, 4> dynamic{
        static_cast<std::uint32_t>(slot * slot_bytes_), static_cast<std::uint32_t>(slot * slot_bytes_),
        static_cast<std::uint32_t>(slot * slot_bytes_), static_cast<std::uint32_t>(slot * slot_bytes_)};
    const std::array<vk::DescriptorSet, 2> sets{*frame_set_, *scene_set_};
    const std::uint32_t shadows = shadow_casters_;

    // The shadows: footprints into the mask at half the size.
    const vk::ClearValue black{.color = vk::ClearColorValue{std::array<float, 4>{0, 0, 0, 0}}};
    cmd.beginRenderPass({.renderPass = *shadow_pass_,
                         .framebuffer = *shadow_framebuffer_,
                         .renderArea = {.offset = {.x = 0, .y = 0}, .extent = half_},
                         .clearValueCount = 1,
                         .pClearValues = &black},
                        vk::SubpassContents::eInline);
    set_viewport(cmd, half_);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *shadow_pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *scene_pipeline_layout_, 0, sets, dynamic);
    push(cmd, *scene_pipeline_layout_, Push{.pass = 2, .first = 0, .count = shadows, .flags = 0, .params = {}});
    cmd.draw(18, shadows, 0, 0);
    ++counters_.draws;
    counters_.triangles += 6ULL * shadows;
    cmd.endRenderPass();
    // The blurs' images: the previous frame's reads done, their content of no use (fully written again).
    const std::array<gpu::ImageDependency, 2> to_general{{
        {.image = blur_a_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eFragmentShader | vk::PipelineStageFlagBits2::eComputeShader,
         .src_access = {},
         .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
         .dst_access = vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
         .old_layout = vk::ImageLayout::eUndefined,
         .new_layout = vk::ImageLayout::eGeneral},
        {.image = blur_b_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eFragmentShader | vk::PipelineStageFlagBits2::eComputeShader,
         .src_access = {},
         .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
         .dst_access = vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
         .old_layout = vk::ImageLayout::eUndefined,
         .new_layout = vk::ImageLayout::eGeneral},
    }};
    sync.barrier(cmd, {}, to_general);
    const std::array<vk::DescriptorSet, 3> shadow_chain{*blur_sets_[0], *blur_sets_[1], *blur_sets_[2]};
    blur(cmd, shadow_chain, half_, shadow_blur, blur_a_.handle(), blur_b_.handle());
    // Six passes end in B (A, B, A, B, A, B): sampled by the main pass's ground.
    sync.barrier(cmd, gpu::ImageDependency{.image = blur_b_.handle(),
                                           .src_stage = vk::PipelineStageFlagBits2::eComputeShader,
                                           .src_access = vk::AccessFlagBits2::eShaderWrite,
                                           .dst_stage = vk::PipelineStageFlagBits2::eFragmentShader,
                                           .dst_access = vk::AccessFlagBits2::eShaderRead,
                                           .old_layout = vk::ImageLayout::eGeneral,
                                           .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal});

    // The water's box: the waterside mirrored, at the configuration's samples, resolved.
    const std::array<vk::ClearValue, 3> water_clears{
        vk::ClearValue{.color = vk::ClearColorValue{std::array<float, 4>{0.36F, 0.50F, 0.56F, 1.0F}}},
        vk::ClearValue{.color = vk::ClearColorValue{std::array<float, 4>{0.36F, 0.50F, 0.56F, 1.0F}}},
        vk::ClearValue{.depthStencil = {.depth = 1.0F, .stencil = 0}}};
    const bool resolve = static_cast<bool>(*water_colour_view_);
    std::array<vk::ClearValue, 3> clears = water_clears;
    if (!resolve) clears[1] = water_clears[2];
    cmd.beginRenderPass({.renderPass = *water_pass_,
                         .framebuffer = *water_framebuffer_,
                         .renderArea = {.offset = {.x = 0, .y = 0}, .extent = water_extent_},
                         .clearValueCount = resolve ? 3U : 2U,
                         .pClearValues = clears.data()},
                        vk::SubpassContents::eInline);
    set_viewport(cmd, water_extent_);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *mirror_pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *scene_pipeline_layout_, 0, sets, dynamic);
    const auto mirrored = static_cast<std::uint32_t>(city.waterside.size());
    push(cmd, *scene_pipeline_layout_, Push{.pass = 1, .first = shadows, .count = mirrored, .flags = 0, .params = {}});
    cmd.draw(30, mirrored, 0, 0);
    ++counters_.draws;
    counters_.triangles += 10ULL * mirrored;
    cmd.endRenderPass();

    // The atlas: 64 crowns a frame, the rest kept (cleared the first time).
    const vk::ClearValue clear_atlas{.color = vk::ClearColorValue{std::array<float, 4>{0, 0, 0, 0}}};
    cmd.beginRenderPass(
        {.renderPass = atlas_ready_ ? *atlas_load_pass_ : *atlas_clear_pass_,
         .framebuffer = *atlas_framebuffer_,
         .renderArea = {.offset = {.x = 0, .y = 0}, .extent = {.width = atlas_size, .height = atlas_size}},
         .clearValueCount = atlas_ready_ ? 0U : 1U,
         .pClearValues = &clear_atlas},
        vk::SubpassContents::eInline);
    set_viewport(cmd, {.width = atlas_size, .height = atlas_size});
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *atlas_pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *scene_pipeline_layout_, 0, sets, dynamic);
    const auto first = static_cast<std::uint32_t>((frame.number * atlas_per_frame) % 256);
    push(cmd, *scene_pipeline_layout_,
         Push{.pass = 3, .first = first, .count = atlas_per_frame, .flags = 0, .params = {}});
    cmd.draw(6, atlas_ready_ ? atlas_per_frame : 256, 0, 0);
    ++counters_.draws;
    counters_.triangles += 2ULL * atlas_per_frame;
    cmd.endRenderPass();
    atlas_ready_ = true;
}

void StandIn::record_main(const gpu::FrameContext& frame, std::uint32_t slot) {
    const vk::raii::CommandBuffer& cmd = frame.cmd;
    const City& city = *city_;
    const auto offset = static_cast<std::uint32_t>(slot * slot_bytes_);
    const std::array<std::uint32_t, 4> dynamic{offset, offset, offset, offset};
    const std::array<vk::DescriptorSet, 2> sets{*frame_set_, *scene_set_};
    frame.pass.begin(cmd, frame.sync, frame.image, {0.78F, 0.76F, 0.70F, 1.0F});
    set_viewport(cmd, extent_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *scene_pipeline_layout_, 0, sets, dynamic);

    // Opaque objects front to back: the boxes in 256 draws over their front-to-back order, then the trees.
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *box_pipeline_);
    const auto boxes = static_cast<std::uint32_t>(city.boxes.size());
    const std::uint32_t per_draw = (boxes + box_chunks - 1) / box_chunks;
    for (std::uint32_t first = 0; first < boxes; first += per_draw) {
        cmd.draw(30, std::min(per_draw, boxes - first), 0, first);
        ++counters_.draws;
    }
    counters_.triangles += city.box_triangles();
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *tree_pipeline_);
    cmd.draw(6, static_cast<std::uint32_t>(city.trees.size()), 0, 0);
    ++counters_.draws;
    counters_.triangles += 2ULL * city.trees.size();
    // The opaque ground under them, then the translucent glows by screen.
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *ground_pipeline_);
    push(cmd, *scene_pipeline_layout_,
         Push{.pass = 0,
              .first = 0,
              .count = 0,
              .flags = 0,
              .params = {static_cast<float>(city.extent), static_cast<float>(city.canal_z),
                         static_cast<float>(city.canal_half), 0.0F}});
    cmd.draw(6, 1, 0, 0);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *glow_pipeline_);
    push(cmd, *scene_pipeline_layout_,
         Push{.pass = 0,
              .first = 0,
              .count = glows,
              .flags = 0,
              .params = {static_cast<float>(city.extent), 0.0F, 0.0F, 90.0F}});
    cmd.draw(6, glows, 0, 0);
    counters_.draws += 2;
    counters_.triangles += 2 + 2ULL * glows;

    if (frame.pass.composite()) {
        frame.pass.next(cmd, frame.sync);
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *composite_pipeline_);
        const std::array<vk::DescriptorSet, 2> composite_sets{*haze_set_, *inputs_set_};
        const std::array<std::uint32_t, 1> haze_offset{offset};
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *composite_pipeline_layout_, 0, composite_sets,
                               haze_offset);
        cmd.draw(3, 1, 0, 0);
        ++counters_.draws;
        counters_.triangles += 1;
    }
    frame.pass.end(cmd, frame.sync, frame.image);
}

void StandIn::record_pills(const gpu::FrameContext& frame) {
    const auto shapes = camera_.keep_outs();
    std::size_t frosted = 0;
    for (const style::KeepOut& k : shapes) frosted += k.frosted() ? 1 : 0;
    if (!pills_ || !frost_ready_ || frosted == 0) return;
    const vk::raii::CommandBuffer& cmd = frame.cmd;
    const gpu::Sync& sync = frame.sync;
    const vk::Image output = frame.output.images()[frame.image];
    // The frame to a quarter-size copy (the blit filters it down), blurred and saturated under each pill.
    const std::array<gpu::ImageDependency, 2> to_copy{{
        {.image = output,
         .src_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
         .src_access = vk::AccessFlagBits2::eColorAttachmentWrite,
         .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
         .dst_access = vk::AccessFlagBits2::eTransferRead,
         .old_layout = output_final_,
         .new_layout = vk::ImageLayout::eTransferSrcOptimal},
        {.image = frost_c_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eFragmentShader | vk::PipelineStageFlagBits2::eComputeShader,
         .src_access = {},
         .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
         .dst_access = vk::AccessFlagBits2::eTransferWrite,
         .old_layout = vk::ImageLayout::eUndefined,
         .new_layout = vk::ImageLayout::eTransferDstOptimal},
    }};
    sync.barrier(cmd, {}, to_copy);
    const vk::ImageBlit blit{
        .srcSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
        .srcOffsets = std::array<vk::Offset3D, 2>{vk::Offset3D{.x = 0, .y = 0, .z = 0},
                                                  vk::Offset3D{.x = static_cast<std::int32_t>(extent_.width),
                                                               .y = static_cast<std::int32_t>(extent_.height),
                                                               .z = 1}},
        .dstSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor, .layerCount = 1},
        .dstOffsets = std::array<vk::Offset3D, 2>{vk::Offset3D{.x = 0, .y = 0, .z = 0},
                                                  vk::Offset3D{.x = static_cast<std::int32_t>(quarter_.width),
                                                               .y = static_cast<std::int32_t>(quarter_.height),
                                                               .z = 1}}};
    cmd.blitImage(output, vk::ImageLayout::eTransferSrcOptimal, frost_c_.handle(), vk::ImageLayout::eTransferDstOptimal,
                  blit, vk::Filter::eLinear);
    const std::array<gpu::ImageDependency, 2> to_blur{{
        {.image = frost_c_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eTransfer,
         .src_access = vk::AccessFlagBits2::eTransferWrite,
         .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
         .dst_access = vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
         .old_layout = vk::ImageLayout::eTransferDstOptimal,
         .new_layout = vk::ImageLayout::eGeneral},
        {.image = frost_d_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eFragmentShader | vk::PipelineStageFlagBits2::eComputeShader,
         .src_access = {},
         .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
         .dst_access = vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
         .old_layout = vk::ImageLayout::eUndefined,
         .new_layout = vk::ImageLayout::eGeneral},
    }};
    sync.barrier(cmd, {}, to_blur);
    // C → D, D → C, ...: six passes end in C.
    const std::array<vk::DescriptorSet, 2> frost_chain{*blur_sets_[3], *blur_sets_[4]};
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *blur_pipeline_);
    for (int pass = 0; pass < 6; ++pass) {
        const vk::Image target = pass % 2 == 0 ? frost_d_.handle() : frost_c_.handle();
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *blur_pipeline_layout_, 0,
                               frost_chain[static_cast<std::size_t>(pass % 2)], {});
        const BlurPush p{
            .size = {static_cast<std::int32_t>(quarter_.width), static_cast<std::int32_t>(quarter_.height)},
            .step = pass < 3 ? std::array<std::int32_t, 2>{1, 0} : std::array<std::int32_t, 2>{0, 1},
            .radius = frost_blur,
            .pad0 = 0,
            .pad1 = 0,
            .pad2 = 0};
        cmd.pushConstants(*blur_pipeline_layout_, vk::ShaderStageFlagBits::eCompute, 0,
                          vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
        cmd.dispatch((quarter_.width + 7) / 8, (quarter_.height + 7) / 8, 1);
        ++counters_.dispatches;
        sync.barrier(cmd, gpu::ImageDependency{.image = target,
                                               .src_stage = vk::PipelineStageFlagBits2::eComputeShader,
                                               .src_access = vk::AccessFlagBits2::eShaderWrite,
                                               .dst_stage = vk::PipelineStageFlagBits2::eComputeShader,
                                               .dst_access =
                                                   vk::AccessFlagBits2::eShaderRead | vk::AccessFlagBits2::eShaderWrite,
                                               .old_layout = vk::ImageLayout::eGeneral,
                                               .new_layout = vk::ImageLayout::eGeneral});
    }
    const std::array<gpu::ImageDependency, 2> to_draw{{
        {.image = frost_c_.handle(),
         .src_stage = vk::PipelineStageFlagBits2::eComputeShader,
         .src_access = vk::AccessFlagBits2::eShaderWrite,
         .dst_stage = vk::PipelineStageFlagBits2::eFragmentShader,
         .dst_access = vk::AccessFlagBits2::eShaderRead,
         .old_layout = vk::ImageLayout::eGeneral,
         .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal},
        {.image = output,
         .src_stage = vk::PipelineStageFlagBits2::eTransfer,
         .src_access = {},
         .dst_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
         .dst_access = vk::AccessFlagBits2::eColorAttachmentRead | vk::AccessFlagBits2::eColorAttachmentWrite,
         .old_layout = vk::ImageLayout::eTransferSrcOptimal,
         .new_layout = vk::ImageLayout::eColorAttachmentOptimal},
    }};
    sync.barrier(cmd, {}, to_draw);
    cmd.beginRenderPass({.renderPass = *frost_pass_,
                         .framebuffer = *frost_framebuffers_[frame.image],
                         .renderArea = {.offset = {.x = 0, .y = 0}, .extent = extent_}},
                        vk::SubpassContents::eInline);
    set_viewport(cmd, extent_);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *frost_pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *frost_pipeline_layout_, 0, *frost_set_, {});
    const double density = camera_.density > 0 ? camera_.density : 1.0;
    const double w = extent_.width;
    const double h = extent_.height;
    // The view's pixels to the output's: through clip space and the surface's pre-rotation, as the shaders turn it.
    const bool turned = (quarter_turns_ & 1U) != 0;
    const double vw = turned ? h : w;
    const double vh = turned ? w : h;
    const std::array<std::array<double, 4>, 4> turns{{{1, 0, 0, 1}, {0, -1, 1, 0}, {-1, 0, 0, -1}, {0, 1, -1, 0}}};
    const auto& rot = turns[quarter_turns_ & 3U];
    auto to_output = [&](double x, double y) {
        const double nx = 2.0 * x / vw - 1.0;
        const double ny = 2.0 * y / vh - 1.0;
        const double rx = rot[0] * nx + rot[1] * ny;
        const double ry = rot[2] * nx + rot[3] * ny;
        return std::array<double, 2>{(rx + 1.0) / 2.0 * w, (ry + 1.0) / 2.0 * h};
    };
    for (const style::KeepOut& k : shapes) {
        if (!k.frosted()) continue;
        const auto a = to_output(static_cast<double>(k.left) * density, static_cast<double>(k.top) * density);
        const auto z = to_output(static_cast<double>(k.right) * density, static_cast<double>(k.bottom) * density);
        const double l = std::min(a[0], z[0]);
        const double t = std::min(a[1], z[1]);
        const double r = std::max(a[0], z[0]);
        const double b = std::max(a[1], z[1]);
        auto f = [](double v) { return static_cast<float>(v); };
        const FrostPush p{.box = {f(l), f(t), f(r), f(b)},
                          .source = {f(l / w), f(t / h), f(r / w), f(b / h)},
                          .screen = {f(w), f(h), f(1.0 / w), f(1.0 / h)},
                          .shape = {f(static_cast<double>(k.radius) * density),
                                    static_cast<float>(k.saturation) / 100.0F, 0.0F, 0.0F}};
        cmd.pushConstants(*frost_pipeline_layout_,
                          vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment, 0,
                          vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
        cmd.draw(6, 1, 0, 0);
        ++counters_.draws;
        counters_.triangles += 2;
    }
    cmd.endRenderPass();
}

void StandIn::record(const gpu::FrameContext& frame) {
    const auto started = clock::now();
    counters_ = {};
    write_frame(frame.slot.slot);
    record_offscreen(frame, frame.slot.slot);
    frame.frames.mark(frame.slot, gpu::Mark::offscreen);
    record_main(frame, frame.slot.slot);
    frame.frames.mark(frame.slot, gpu::Mark::main);
    record_pills(frame);
    frame.frames.mark(frame.slot, gpu::Mark::after);
    counters_.record_ms = ms_since(started);
}

} // namespace atlas::standin
