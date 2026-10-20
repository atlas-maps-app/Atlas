// The main pass in both routes and every configuration (gpu/Passes.hpp).
#include "gpu/Passes.hpp"

#include "base/assert.hpp"

#include <array>
#include <format>

namespace atlas::gpu {

namespace {

constexpr vk::ImageUsageFlags attachment_usage =
    vk::ImageUsageFlagBits::eInputAttachment | vk::ImageUsageFlagBits::eTransientAttachment;

constexpr vk::PipelineStageFlags fragment_tests =
    vk::PipelineStageFlagBits::eEarlyFragmentTests | vk::PipelineStageFlagBits::eLateFragmentTests;
constexpr vk::PipelineStageFlags2 fragment_tests2 =
    vk::PipelineStageFlagBits2::eEarlyFragmentTests | vk::PipelineStageFlagBits2::eLateFragmentTests;

/// One render pass, written in VK_KHR_create_renderpass2's terms and converted for devices without it.
struct PassDescription {
    std::vector<vk::AttachmentDescription2> attachments;
    std::vector<std::vector<vk::AttachmentReference2>> inputs, colours, resolves;
    std::vector<std::optional<vk::AttachmentReference2>> depths;
    std::vector<vk::SubpassDependency2> dependencies;
};

[[nodiscard]] vk::AttachmentReference2 ref(std::uint32_t attachment, vk::ImageLayout layout,
                                           vk::ImageAspectFlags aspect = {}) {
    return {.attachment = attachment, .layout = layout, .aspectMask = aspect};
}

/// The dependency that orders this pass's attachment writes after the previous frame's (the transient attachments are
/// shared by the frames in flight) and after the acquire semaphore's wait.
[[nodiscard]] vk::SubpassDependency2 from_external(std::uint32_t subpass) {
    return {
        .srcSubpass = vk::SubpassExternal,
        .dstSubpass = subpass,
        .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput | fragment_tests,
        .dstStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput | fragment_tests |
                        vk::PipelineStageFlagBits::eFragmentShader,
        .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eDepthStencilAttachmentWrite,
        .dstAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eColorAttachmentRead |
                         vk::AccessFlagBits::eDepthStencilAttachmentWrite |
                         vk::AccessFlagBits::eDepthStencilAttachmentRead | vk::AccessFlagBits::eInputAttachmentRead};
}

/// The scene's writes, visible to the composite's reads of the same pixel, kept in tile memory (by region).
[[nodiscard]] vk::SubpassDependency2 scene_to_composite() {
    return {
        .srcSubpass = 0,
        .dstSubpass = 1,
        .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput | fragment_tests,
        .dstStageMask = vk::PipelineStageFlagBits::eFragmentShader | vk::PipelineStageFlagBits::eColorAttachmentOutput |
                        fragment_tests,
        .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eDepthStencilAttachmentWrite,
        // The depth-stencil's store at the end of the composite (DONT_CARE is a write) follows its change of layout.
        .dstAccessMask = vk::AccessFlagBits::eInputAttachmentRead | vk::AccessFlagBits::eColorAttachmentRead |
                         vk::AccessFlagBits::eColorAttachmentWrite | vk::AccessFlagBits::eDepthStencilAttachmentRead |
                         vk::AccessFlagBits::eDepthStencilAttachmentWrite,
        .dependencyFlags = vk::DependencyFlagBits::eByRegion};
}

/// The output's writes, visible to what follows the pass (the frosted backdrops' copies, a readback, the present).
[[nodiscard]] vk::SubpassDependency2 to_external(std::uint32_t subpass) {
    return {.srcSubpass = subpass,
            .dstSubpass = vk::SubpassExternal,
            .srcStageMask = vk::PipelineStageFlagBits::eColorAttachmentOutput,
            .dstStageMask = vk::PipelineStageFlagBits::eTransfer | vk::PipelineStageFlagBits::eFragmentShader |
                            vk::PipelineStageFlagBits::eColorAttachmentOutput,
            .srcAccessMask = vk::AccessFlagBits::eColorAttachmentWrite,
            .dstAccessMask = vk::AccessFlagBits::eTransferRead | vk::AccessFlagBits::eShaderRead |
                             vk::AccessFlagBits::eColorAttachmentRead | vk::AccessFlagBits::eColorAttachmentWrite};
}

/// Creates the render pass through VK_KHR_create_renderpass2, or Vulkan 1.0's call with the stencil aspect of the input
/// attachments given by VkRenderPassInputAttachmentAspectCreateInfo (core in 1.1).
[[nodiscard]] Result<vk::raii::RenderPass> create_pass(const Device& device, const PassDescription& d) {
    const auto n = static_cast<std::uint32_t>(d.colours.size());
    std::vector<vk::SubpassDescription2> subpasses(n);
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::optional<vk::AttachmentReference2>& depth = d.depths[i];
        subpasses[i] = {.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                        .inputAttachmentCount = static_cast<std::uint32_t>(d.inputs[i].size()),
                        .pInputAttachments = d.inputs[i].data(),
                        .colorAttachmentCount = static_cast<std::uint32_t>(d.colours[i].size()),
                        .pColorAttachments = d.colours[i].data(),
                        .pResolveAttachments = d.resolves[i].empty() ? nullptr : d.resolves[i].data(),
                        .pDepthStencilAttachment = depth ? &*depth : nullptr};
    }
    if (device.enabled().create_renderpass2) {
        ATLAS_VK_LET(
            pass,
            device.handle().createRenderPass2({.attachmentCount = static_cast<std::uint32_t>(d.attachments.size()),
                                               .pAttachments = d.attachments.data(),
                                               .subpassCount = n,
                                               .pSubpasses = subpasses.data(),
                                               .dependencyCount = static_cast<std::uint32_t>(d.dependencies.size()),
                                               .pDependencies = d.dependencies.data()}),
            "the main render pass");
        return std::move(pass);
    }
    // Vulkan 1.0's form.
    std::vector<vk::AttachmentDescription> attachments;
    attachments.reserve(d.attachments.size());
    for (const vk::AttachmentDescription2& a : d.attachments) {
        attachments.push_back({.format = a.format,
                               .samples = a.samples,
                               .loadOp = a.loadOp,
                               .storeOp = a.storeOp,
                               .stencilLoadOp = a.stencilLoadOp,
                               .stencilStoreOp = a.stencilStoreOp,
                               .initialLayout = a.initialLayout,
                               .finalLayout = a.finalLayout});
    }
    auto refs = [](const std::vector<vk::AttachmentReference2>& in) {
        std::vector<vk::AttachmentReference> out;
        out.reserve(in.size());
        for (const vk::AttachmentReference2& r : in) out.push_back({.attachment = r.attachment, .layout = r.layout});
        return out;
    };
    std::vector<std::vector<vk::AttachmentReference>> inputs(n);
    std::vector<std::vector<vk::AttachmentReference>> colours(n);
    std::vector<std::vector<vk::AttachmentReference>> resolves(n);
    std::vector<vk::AttachmentReference> depths(n);
    std::vector<vk::SubpassDescription> subs(n);
    std::vector<vk::InputAttachmentAspectReference> aspects;
    for (std::uint32_t i = 0; i < n; ++i) {
        inputs[i] = refs(d.inputs[i]);
        colours[i] = refs(d.colours[i]);
        resolves[i] = refs(d.resolves[i]);
        const std::optional<vk::AttachmentReference2>& depth = d.depths[i];
        if (depth) depths[i] = {.attachment = depth->attachment, .layout = depth->layout};
        for (std::uint32_t k = 0; k < d.inputs[i].size(); ++k) {
            aspects.push_back({.subpass = i, .inputAttachmentIndex = k, .aspectMask = d.inputs[i][k].aspectMask});
        }
        subs[i] = {.pipelineBindPoint = vk::PipelineBindPoint::eGraphics,
                   .inputAttachmentCount = static_cast<std::uint32_t>(inputs[i].size()),
                   .pInputAttachments = inputs[i].data(),
                   .colorAttachmentCount = static_cast<std::uint32_t>(colours[i].size()),
                   .pColorAttachments = colours[i].data(),
                   .pResolveAttachments = resolves[i].empty() ? nullptr : resolves[i].data(),
                   .pDepthStencilAttachment = depth ? &depths[i] : nullptr};
    }
    std::vector<vk::SubpassDependency> dependencies;
    dependencies.reserve(d.dependencies.size());
    for (const vk::SubpassDependency2& x : d.dependencies) {
        dependencies.push_back({.srcSubpass = x.srcSubpass,
                                .dstSubpass = x.dstSubpass,
                                .srcStageMask = x.srcStageMask,
                                .dstStageMask = x.dstStageMask,
                                .srcAccessMask = x.srcAccessMask,
                                .dstAccessMask = x.dstAccessMask,
                                .dependencyFlags = x.dependencyFlags});
    }
    const vk::RenderPassInputAttachmentAspectCreateInfo aspect_info{
        .aspectReferenceCount = static_cast<std::uint32_t>(aspects.size()), .pAspectReferences = aspects.data()};
    ATLAS_VK_LET(pass,
                 device.handle().createRenderPass({.pNext = aspects.empty() ? nullptr : &aspect_info,
                                                   .attachmentCount = static_cast<std::uint32_t>(attachments.size()),
                                                   .pAttachments = attachments.data(),
                                                   .subpassCount = n,
                                                   .pSubpasses = subs.data(),
                                                   .dependencyCount = static_cast<std::uint32_t>(dependencies.size()),
                                                   .pDependencies = dependencies.data()}),
                 "the main render pass");
    return std::move(pass);
}

[[nodiscard]] Result<vk::raii::ImageView> make_view(const Device& device, vk::Image image, vk::Format format,
                                                    vk::ImageAspectFlags aspect) {
    ATLAS_VK_LET(
        view,
        device.handle().createImageView({.image = image,
                                         .viewType = vk::ImageViewType::e2D,
                                         .format = format,
                                         .subresourceRange = {.aspectMask = aspect, .levelCount = 1, .layerCount = 1}}),
        "an attachment's view");
    return std::move(view);
}

} // namespace

Result<MainPass> MainPass::create(const Device& device, const MainPassSpec& spec,
                                  std::span<const vk::ImageView> outputs, std::span<const vk::Image> output_images) {
    MainPass pass;
    pass.spec_ = spec;
    pass.outputs_.assign(outputs.begin(), outputs.end());
    pass.output_images_.assign(output_images.begin(), output_images.end());
    const Configuration& c = spec.config;
    const vk::SampleCountFlagBits samples = c.samples();
    pass.one_sample_ = samples == vk::SampleCountFlagBits::e1;
    if (c.route == Route::dynamic && !device.enabled().local_read && c.form == FrameForm::composite) {
        return fail("the dynamic route needs local reads");
    }
    if (c.route == Route::dynamic && c.target == CompositeTarget::direct && !pass.one_sample_) {
        return fail("dynamic rendering cannot write a 1x image from samples");
    }
    if (c.route == Route::dynamic && pass.one_sample_ && c.form == FrameForm::composite &&
        !device.enabled().independent_blend) {
        return fail("the dynamic route at 1x needs independentBlend");
    }
    const MemoryUse memory = c.lazy() ? MemoryUse::lazy : MemoryUse::gpu;
    const vk::Extent3D extent{.width = spec.extent.width, .height = spec.extent.height, .depth = 1};

    // The colour samples (absent when the one-subpass form at 1x draws straight into the output).
    const bool colour_image = !pass.one_sample_ || c.form != FrameForm::onepass;
    if (colour_image) {
        ATLAS_GPU_LET(colour,
                      device.memory().image({.imageType = vk::ImageType::e2D,
                                             .format = spec.colour_format,
                                             .extent = extent,
                                             .mipLevels = 1,
                                             .arrayLayers = 1,
                                             .samples = samples,
                                             .tiling = vk::ImageTiling::eOptimal,
                                             .usage = vk::ImageUsageFlagBits::eColorAttachment | attachment_usage,
                                             .initialLayout = vk::ImageLayout::eUndefined},
                                            memory));
        pass.colour_ = std::move(colour);
        ATLAS_GPU_LET(view,
                      make_view(device, pass.colour_.handle(), spec.colour_format, vk::ImageAspectFlagBits::eColor));
        pass.colour_view_ = std::move(view);
    }
    ATLAS_GPU_LET(depth,
                  device.memory().image({.imageType = vk::ImageType::e2D,
                                         .format = spec.depth_stencil,
                                         .extent = extent,
                                         .mipLevels = 1,
                                         .arrayLayers = 1,
                                         .samples = samples,
                                         .tiling = vk::ImageTiling::eOptimal,
                                         .usage = vk::ImageUsageFlagBits::eDepthStencilAttachment | attachment_usage,
                                         .initialLayout = vk::ImageLayout::eUndefined},
                                        memory));
    pass.depth_ = std::move(depth);
    ATLAS_GPU_LET(depth_view, make_view(device, pass.depth_.handle(), spec.depth_stencil,
                                        vk::ImageAspectFlagBits::eDepth | vk::ImageAspectFlagBits::eStencil));
    pass.depth_view_ = std::move(depth_view);
    ATLAS_GPU_LET(stencil_view,
                  make_view(device, pass.depth_.handle(), spec.depth_stencil, vk::ImageAspectFlagBits::eStencil));
    pass.stencil_view_ = std::move(stencil_view);

    if (c.route == Route::renderpass) {
        ATLAS_GPU_CHECK(pass.make_render_pass(device, outputs));
    } else {
        pass.make_dynamic_targets(device);
    }
    return pass;
}

Result<void> MainPass::make_render_pass(const Device& device, std::span<const vk::ImageView> outputs) {
    const Configuration& c = spec_.config;
    const vk::SampleCountFlagBits samples = c.samples();
    PassDescription d;
    const vk::AttachmentDescription2 colour{.format = spec_.colour_format,
                                            .samples = samples,
                                            .loadOp = vk::AttachmentLoadOp::eClear,
                                            .storeOp = vk::AttachmentStoreOp::eDontCare,
                                            .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
                                            .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                                            .initialLayout = vk::ImageLayout::eUndefined,
                                            .finalLayout = vk::ImageLayout::eColorAttachmentOptimal};
    const vk::AttachmentDescription2 depth{.format = spec_.depth_stencil,
                                           .samples = samples,
                                           .loadOp = vk::AttachmentLoadOp::eClear,
                                           .storeOp = vk::AttachmentStoreOp::eDontCare,
                                           .stencilLoadOp = vk::AttachmentLoadOp::eClear,
                                           .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                                           .initialLayout = vk::ImageLayout::eUndefined,
                                           .finalLayout = vk::ImageLayout::eDepthStencilAttachmentOptimal};
    vk::AttachmentDescription2 output{.format = spec_.output_format,
                                      .samples = vk::SampleCountFlagBits::e1,
                                      .loadOp = vk::AttachmentLoadOp::eDontCare,
                                      .storeOp = vk::AttachmentStoreOp::eStore,
                                      .stencilLoadOp = vk::AttachmentLoadOp::eDontCare,
                                      .stencilStoreOp = vk::AttachmentStoreOp::eDontCare,
                                      .initialLayout = vk::ImageLayout::eUndefined,
                                      .finalLayout = spec_.output_final};
    const vk::ImageLayout colour_write = vk::ImageLayout::eColorAttachmentOptimal;
    const vk::ImageLayout ds_write = vk::ImageLayout::eDepthStencilAttachmentOptimal;
    const vk::ImageLayout ds_read = vk::ImageLayout::eDepthStencilReadOnlyOptimal;
    const vk::ImageAspectFlags colour_aspect = vk::ImageAspectFlagBits::eColor;
    const vk::ImageAspectFlags stencil_aspect = vk::ImageAspectFlagBits::eStencil;
    // Attachment indices in the framebuffer.
    std::vector<vk::ImageView> views;

    if (c.form == FrameForm::onepass) {
        d.inputs.resize(1);
        d.colours.resize(1);
        d.resolves.resize(1);
        d.depths.resize(1);
        if (one_sample_) {
            // The output is the colour attachment; the depth-stencil beside it.
            output.loadOp = vk::AttachmentLoadOp::eClear;
            d.attachments = {output, depth};
            d.colours[0] = {ref(0, colour_write)};
            d.depths[0] = ref(1, ds_write);
            views = {vk::ImageView{}, *depth_view_};
        } else {
            d.attachments = {colour, depth, output};
            d.colours[0] = {ref(0, colour_write)};
            d.resolves[0] = {ref(2, colour_write)};
            d.depths[0] = ref(1, ds_write);
            views = {*colour_view_, *depth_view_, vk::ImageView{}};
        }
        d.dependencies = {from_external(0), to_external(0)};
    } else {
        d.inputs.resize(2);
        d.colours.resize(2);
        d.resolves.resize(2);
        d.depths.resize(2);
        d.colours[0] = {ref(0, colour_write)};
        d.depths[0] = ref(1, ds_write);
        if (one_sample_ || c.target == CompositeTarget::direct) {
            // The composite reads the scene's attachments and writes the output itself.
            vk::AttachmentDescription2 scene_colour = colour;
            scene_colour.finalLayout = vk::ImageLayout::eShaderReadOnlyOptimal;
            vk::AttachmentDescription2 scene_depth = depth;
            scene_depth.finalLayout = ds_read;
            d.attachments = {scene_colour, scene_depth, output};
            colour_input_layout_ = vk::ImageLayout::eShaderReadOnlyOptimal;
            stencil_input_layout_ = ds_read;
            d.inputs[1] = {ref(0, colour_input_layout_, colour_aspect), ref(1, ds_read, stencil_aspect)};
            d.colours[1] = {ref(2, colour_write)};
            // At one sample count the depth-stencil stays bound, read-only (Arm's merge condition, D5.3).
            if (one_sample_) d.depths[1] = ref(1, ds_read);
        } else {
            // The composite reads and writes the samples in place (a feedback loop in the general layout) and the
            // samples resolve into the output at the end of the subpass, on chip.
            vk::AttachmentDescription2 scene_colour = colour;
            scene_colour.finalLayout = vk::ImageLayout::eGeneral;
            vk::AttachmentDescription2 scene_depth = depth;
            scene_depth.finalLayout = ds_read;
            d.attachments = {scene_colour, scene_depth, output};
            colour_input_layout_ = vk::ImageLayout::eGeneral;
            stencil_input_layout_ = ds_read;
            d.inputs[1] = {ref(0, vk::ImageLayout::eGeneral, colour_aspect), ref(1, ds_read, stencil_aspect)};
            d.colours[1] = {ref(0, vk::ImageLayout::eGeneral)};
            d.resolves[1] = {ref(2, colour_write)};
            d.depths[1] = ref(1, ds_read);
        }
        d.dependencies = {from_external(0), from_external(1), scene_to_composite(), to_external(1)};
        views = {*colour_view_, *depth_view_, vk::ImageView{}};
    }
    ATLAS_GPU_LET(render_pass, create_pass(device, d));
    render_pass_ = std::move(render_pass);

    // One framebuffer per output image: the output's slot takes that image's view.
    const std::size_t output_slot = (c.form == FrameForm::onepass && one_sample_) ? 0 : 2;
    framebuffers_.clear();
    for (const vk::ImageView output_view : outputs) {
        views[output_slot] = output_view;
        ATLAS_VK_LET(framebuffer,
                     device.handle().createFramebuffer({.renderPass = *render_pass_,
                                                        .attachmentCount = static_cast<std::uint32_t>(views.size()),
                                                        .pAttachments = views.data(),
                                                        .width = spec_.extent.width,
                                                        .height = spec_.extent.height,
                                                        .layers = 1}),
                     "a framebuffer of the main pass");
        framebuffers_.push_back(std::move(framebuffer));
    }

    scene_ = {.render_pass = *render_pass_, .subpass = 0, .samples = samples, .colour_count = 1};
    if (c.form == FrameForm::composite) {
        const bool writes_output = one_sample_ || c.target == CompositeTarget::direct;
        composite_ = {.render_pass = *render_pass_,
                      .subpass = 1,
                      .samples = writes_output ? vk::SampleCountFlagBits::e1 : samples,
                      .colour_count = 1};
    }
    return {};
}

void MainPass::make_dynamic_targets(const Device& device) {
    (void)device;
    const Configuration& c = spec_.config;
    const vk::SampleCountFlagBits samples = c.samples();
    PipelineTarget t{.samples = samples, .depth_format = spec_.depth_stencil, .stencil_format = spec_.depth_stencil};
    if (c.form == FrameForm::onepass) {
        t.colour_formats = {one_sample_ ? spec_.output_format : spec_.colour_format};
        t.colour_count = 1;
        scene_ = t;
        return;
    }
    // Local reads: colour attachment 0 is input 0, the stencil input 1; at 1x the output is colour attachment 1, which
    // only the composite writes.
    colour_input_layout_ = vk::ImageLayout::eRenderingLocalRead;
    stencil_input_layout_ = vk::ImageLayout::eRenderingLocalRead;
    t.local_read = true;
    t.stencil_input_index = 1;
    if (one_sample_) {
        t.colour_formats = {spec_.colour_format, spec_.output_format};
        t.colour_input_indices = {0, vk::AttachmentUnused};
        t.colour_count = 2;
    } else {
        t.colour_formats = {spec_.colour_format};
        t.colour_input_indices = {0};
        t.colour_count = 1;
    }
    scene_ = t;
    composite_ = t;
}

void MainPass::begin(const vk::raii::CommandBuffer& cmd, const Sync& sync, std::uint32_t index,
                     const std::array<float, 4>& clear, float clear_depth) const {
    const Configuration& c = spec_.config;
    const vk::ClearValue colour_clear{.color = vk::ClearColorValue{clear}};
    const vk::ClearValue depth_clear{.depthStencil = {.depth = clear_depth, .stencil = 0}};
    const vk::Rect2D area{.offset = {.x = 0, .y = 0}, .extent = spec_.extent};
    if (*render_pass_) {
        // Attachment 0 is colour (the samples, or the output itself at 1x in one subpass), 1 the depth-stencil, 2 the
        // output where it is separate; only cleared attachments read their value.
        const std::array<vk::ClearValue, 3> clears{colour_clear, depth_clear, colour_clear};
        cmd.beginRenderPass({.renderPass = *render_pass_,
                             .framebuffer = *framebuffers_[index],
                             .renderArea = area,
                             .clearValueCount = static_cast<std::uint32_t>(clears.size()),
                             .pClearValues = clears.data()},
                            vk::SubpassContents::eInline);
        return;
    }

    // Dynamic rendering: the layouts the scope uses, after the previous frame's use of the shared attachments and the
    // acquire semaphore's wait.
    const bool local = c.form == FrameForm::composite;
    const vk::ImageLayout colour_layout =
        local ? vk::ImageLayout::eRenderingLocalRead : vk::ImageLayout::eColorAttachmentOptimal;
    const vk::ImageLayout depth_layout =
        local ? vk::ImageLayout::eRenderingLocalRead : vk::ImageLayout::eDepthStencilAttachmentOptimal;
    std::array<ImageDependency, 3> barriers{};
    std::size_t n = 0;
    const vk::PipelineStageFlags2 colour_stages =
        vk::PipelineStageFlagBits2::eColorAttachmentOutput | vk::PipelineStageFlagBits2::eFragmentShader;
    const vk::AccessFlags2 colour_access = vk::AccessFlagBits2::eColorAttachmentWrite |
                                           vk::AccessFlagBits2::eColorAttachmentRead |
                                           vk::AccessFlagBits2::eInputAttachmentRead;
    if (colour_) {
        barriers[n++] = {.image = colour_.handle(),
                         .aspect = vk::ImageAspectFlagBits::eColor,
                         .src_stage = colour_stages,
                         .src_access = vk::AccessFlagBits2::eColorAttachmentWrite,
                         .dst_stage = colour_stages,
                         .dst_access = colour_access,
                         .old_layout = vk::ImageLayout::eUndefined,
                         .new_layout = colour_layout};
    }
    barriers[n++] = {.image = depth_.handle(),
                     .aspect = vk::ImageAspectFlagBits::eDepth | vk::ImageAspectFlagBits::eStencil,
                     .src_stage = fragment_tests2,
                     .src_access = vk::AccessFlagBits2::eDepthStencilAttachmentWrite,
                     .dst_stage = fragment_tests2 | vk::PipelineStageFlagBits2::eFragmentShader,
                     .dst_access = vk::AccessFlagBits2::eDepthStencilAttachmentWrite |
                                   vk::AccessFlagBits2::eDepthStencilAttachmentRead |
                                   vk::AccessFlagBits2::eInputAttachmentRead,
                     .old_layout = vk::ImageLayout::eUndefined,
                     .new_layout = depth_layout};
    barriers[n++] = {.image = output_images_[index],
                     .aspect = vk::ImageAspectFlagBits::eColor,
                     .src_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                     .src_access = {},
                     .dst_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                     .dst_access =
                         vk::AccessFlagBits2::eColorAttachmentWrite | vk::AccessFlagBits2::eColorAttachmentRead,
                     .old_layout = vk::ImageLayout::eUndefined,
                     .new_layout = vk::ImageLayout::eColorAttachmentOptimal};
    sync.barrier(cmd, {}, std::span(barriers.data(), n));

    std::array<vk::RenderingAttachmentInfo, 2> colours{};
    std::uint32_t colour_count = 1;
    if (c.form == FrameForm::onepass && one_sample_) {
        colours[0] = {.imageView = outputs_[index],
                      .imageLayout = vk::ImageLayout::eColorAttachmentOptimal,
                      .loadOp = vk::AttachmentLoadOp::eClear,
                      .storeOp = vk::AttachmentStoreOp::eStore,
                      .clearValue = colour_clear};
    } else if (one_sample_) {
        colours[0] = {.imageView = *colour_view_,
                      .imageLayout = colour_layout,
                      .loadOp = vk::AttachmentLoadOp::eClear,
                      .storeOp = vk::AttachmentStoreOp::eDontCare,
                      .clearValue = colour_clear};
        colours[1] = {.imageView = outputs_[index],
                      .imageLayout = vk::ImageLayout::eColorAttachmentOptimal,
                      .loadOp = vk::AttachmentLoadOp::eDontCare,
                      .storeOp = vk::AttachmentStoreOp::eStore,
                      .clearValue = colour_clear};
        colour_count = 2;
    } else {
        colours[0] = {.imageView = *colour_view_,
                      .imageLayout = colour_layout,
                      .resolveMode = vk::ResolveModeFlagBits::eAverage,
                      .resolveImageView = outputs_[index],
                      .resolveImageLayout = vk::ImageLayout::eColorAttachmentOptimal,
                      .loadOp = vk::AttachmentLoadOp::eClear,
                      .storeOp = vk::AttachmentStoreOp::eDontCare,
                      .clearValue = colour_clear};
    }
    const vk::RenderingAttachmentInfo depth{.imageView = *depth_view_,
                                            .imageLayout = depth_layout,
                                            .loadOp = vk::AttachmentLoadOp::eClear,
                                            .storeOp = vk::AttachmentStoreOp::eDontCare,
                                            .clearValue = depth_clear};
    cmd.beginRendering({.renderArea = area,
                        .layerCount = 1,
                        .colorAttachmentCount = colour_count,
                        .pColorAttachments = colours.data(),
                        .pDepthAttachment = &depth,
                        .pStencilAttachment = &depth});
    if (local) {
        const std::uint32_t stencil_index = 1;
        const vk::RenderingInputAttachmentIndexInfo inputs{
            .colorAttachmentCount = static_cast<std::uint32_t>(scene_.colour_input_indices.size()),
            .pColorAttachmentInputIndices = scene_.colour_input_indices.data(),
            .pDepthInputAttachmentIndex = nullptr,
            .pStencilInputAttachmentIndex = &stencil_index};
        cmd.setRenderingInputAttachmentIndices(inputs);
    }
}

void MainPass::next(const vk::raii::CommandBuffer& cmd, const Sync& sync) const {
    if (*render_pass_) {
        cmd.nextSubpass(vk::SubpassContents::eInline);
        return;
    }
    // Inside the scope: the scene's colour and depth-stencil writes, visible to the composite's local reads of the same
    // pixel (by region, so a tiler keeps them on chip).
    sync.barrier(cmd,
                 MemoryDependency{.src_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput | fragment_tests2,
                                  .src_access = vk::AccessFlagBits2::eColorAttachmentWrite |
                                                vk::AccessFlagBits2::eDepthStencilAttachmentWrite,
                                  .dst_stage = vk::PipelineStageFlagBits2::eFragmentShader,
                                  .dst_access = vk::AccessFlagBits2::eInputAttachmentRead},
                 vk::DependencyFlagBits::eByRegion);
}

void MainPass::end(const vk::raii::CommandBuffer& cmd, const Sync& sync, std::uint32_t index) const {
    if (*render_pass_) {
        cmd.endRenderPass();
        return;
    }
    cmd.endRendering();
    sync.barrier(
        cmd, ImageDependency{
                 .image = output_images_[index],
                 .aspect = vk::ImageAspectFlagBits::eColor,
                 .src_stage = vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                 .src_access = vk::AccessFlagBits2::eColorAttachmentWrite,
                 .dst_stage = vk::PipelineStageFlagBits2::eTransfer | vk::PipelineStageFlagBits2::eFragmentShader |
                              vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                 .dst_access = vk::AccessFlagBits2::eTransferRead | vk::AccessFlagBits2::eShaderRead |
                               vk::AccessFlagBits2::eColorAttachmentRead | vk::AccessFlagBits2::eColorAttachmentWrite,
                 .old_layout = vk::ImageLayout::eColorAttachmentOptimal,
                 .new_layout = spec_.output_final});
}

} // namespace atlas::gpu
