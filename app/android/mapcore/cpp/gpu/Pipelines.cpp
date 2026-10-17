// Pipelines (gpu/Pipelines.hpp).
#include "gpu/Pipelines.hpp"

#include "base/assert.hpp"
#include "base/log.hpp"

#include <format>

namespace atlas::gpu {

std::string describe(const PipelineLabel& label) {
    return std::format("{} (module {}, configuration {})", label.name, label.module, label.configuration);
}

void announce(const PipelineLabel& label, PipelineWatch* watch) {
    log::info("vk pipeline {}", describe(label));
    if (watch != nullptr) watch->before(label);
}

void announce_module(std::string_view module, PipelineWatch* watch) {
    log::info("vk module {}", module);
    if (watch != nullptr) watch->before_module(module);
}

std::string describe(const Release& release) {
    return release.detail.empty() ? std::string(release.kind) : std::format("{} {}", release.kind, release.detail);
}

void announce_release(const Release& release, PipelineWatch* watch) {
    log::info("vk release {}", describe(release));
    if (watch != nullptr) watch->before_release(release);
}

bool Blend::reads_destination_alpha() const noexcept {
    if (!enable) return false;
    auto reads = [](vk::BlendFactor f) {
        return f == vk::BlendFactor::eDstAlpha || f == vk::BlendFactor::eOneMinusDstAlpha ||
               f == vk::BlendFactor::eSrcAlphaSaturate;
    };
    return reads(src_colour) || reads(dst_colour);
}

Result<vk::raii::ShaderModule> make_module(const vk::raii::Device& device, std::span<const std::uint32_t> spirv) {
    ATLAS_VK_LET(module, device.createShaderModule({.codeSize = spirv.size_bytes(), .pCode = spirv.data()}),
                 "a shader module");
    return std::move(module);
}

Result<vk::raii::PipelineLayout> make_layout(const vk::raii::Device& device,
                                             std::span<const vk::DescriptorSetLayout> sets, std::uint32_t push_bytes,
                                             vk::ShaderStageFlags push_stages) {
    const vk::PushConstantRange range{.stageFlags = push_stages, .offset = 0, .size = push_bytes};
    ATLAS_VK_LET(layout,
                 device.createPipelineLayout({.setLayoutCount = static_cast<std::uint32_t>(sets.size()),
                                              .pSetLayouts = sets.data(),
                                              .pushConstantRangeCount = push_bytes > 0 ? 1U : 0U,
                                              .pPushConstantRanges = push_bytes > 0 ? &range : nullptr}),
                 "a pipeline layout");
    return std::move(layout);
}

Result<vk::raii::Pipeline> make_graphics(const vk::raii::Device& device, const vk::raii::PipelineCache& cache,
                                         const GraphicsDesc& d) {
    ATLAS_ASSERT(d.target.colour_count <= d.blends.size(), "at most four colour attachments");
    for (std::uint32_t i = 0; i < d.target.colour_count; ++i) {
        if (d.blends[i].reads_destination_alpha()) {
            return fail(std::format("the graphics pipeline {} blends attachment {} by the destination's alpha, which "
                                    "the frame's colour does not keep (rgb10a2 holds 2 bits of it)",
                                    describe(d.label), i));
        }
    }
    const std::array<vk::PipelineShaderStageCreateInfo, 2> stages{{
        {.stage = vk::ShaderStageFlagBits::eVertex, .module = d.module, .pName = d.vertex},
        {.stage = vk::ShaderStageFlagBits::eFragment,
         .module = d.fragment_module ? d.fragment_module : d.module,
         .pName = d.fragment},
    }};
    const vk::PipelineVertexInputStateCreateInfo
        vertex_input{}; // every pipeline pulls its vertices from storage buffers
    const vk::PipelineInputAssemblyStateCreateInfo assembly{.topology = d.topology};
    const vk::PipelineViewportStateCreateInfo viewport{.viewportCount = 1, .scissorCount = 1};
    const vk::PipelineRasterizationStateCreateInfo raster{
        .polygonMode = vk::PolygonMode::eFill, .cullMode = d.cull, .frontFace = d.front, .lineWidth = 1.0F};
    const vk::PipelineMultisampleStateCreateInfo multisample{
        .rasterizationSamples = d.target.samples, .alphaToCoverageEnable = d.alpha_to_coverage ? vk::True : vk::False};
    const vk::PipelineDepthStencilStateCreateInfo depth{.depthTestEnable = d.depth_test ? vk::True : vk::False,
                                                        .depthWriteEnable = d.depth_write ? vk::True : vk::False,
                                                        .depthCompareOp = d.depth_op,
                                                        .stencilTestEnable = d.stencil_test ? vk::True : vk::False,
                                                        .front = d.stencil,
                                                        .back = d.stencil};
    std::array<vk::PipelineColorBlendAttachmentState, 4> attachments{};
    for (std::uint32_t i = 0; i < d.target.colour_count; ++i) {
        const Blend& b = d.blends[i];
        attachments[i] = {.blendEnable = b.enable ? vk::True : vk::False,
                          .srcColorBlendFactor = b.src_colour,
                          .dstColorBlendFactor = b.dst_colour,
                          .colorBlendOp = vk::BlendOp::eAdd,
                          .srcAlphaBlendFactor = b.src_alpha,
                          .dstAlphaBlendFactor = b.dst_alpha,
                          .alphaBlendOp = vk::BlendOp::eAdd,
                          .colorWriteMask = b.mask};
    }
    const vk::PipelineColorBlendStateCreateInfo blend{.attachmentCount = d.target.colour_count,
                                                      .pAttachments = attachments.data()};
    const std::array<vk::DynamicState, 2> dynamic_states{vk::DynamicState::eViewport, vk::DynamicState::eScissor};
    const vk::PipelineDynamicStateCreateInfo dynamic{.dynamicStateCount =
                                                         static_cast<std::uint32_t>(dynamic_states.size()),
                                                     .pDynamicStates = dynamic_states.data()};

    // Dynamic rendering: the formats, and the local-read mapping of the attachments to input indices.
    vk::PipelineRenderingCreateInfo rendering{.colorAttachmentCount =
                                                  static_cast<std::uint32_t>(d.target.colour_formats.size()),
                                              .pColorAttachmentFormats = d.target.colour_formats.data(),
                                              .depthAttachmentFormat = d.target.depth_format,
                                              .stencilAttachmentFormat = d.target.stencil_format};
    const std::uint32_t depth_index = d.target.depth_input_index.value_or(0);
    const std::uint32_t stencil_index = d.target.stencil_input_index.value_or(0);
    vk::RenderingInputAttachmentIndexInfo inputs{
        .colorAttachmentCount = static_cast<std::uint32_t>(d.target.colour_input_indices.size()),
        .pColorAttachmentInputIndices =
            d.target.colour_input_indices.empty() ? nullptr : d.target.colour_input_indices.data(),
        .pDepthInputAttachmentIndex = d.target.depth_input_index ? &depth_index : nullptr,
        .pStencilInputAttachmentIndex = d.target.stencil_input_index ? &stencil_index : nullptr};
    const bool dynamic_route = !d.target.render_pass;
    if (dynamic_route && d.target.local_read) rendering.pNext = &inputs;

    ATLAS_VK_LET(pipeline,
                 device.createGraphicsPipeline(cache, {.pNext = dynamic_route ? &rendering : nullptr,
                                                       .stageCount = static_cast<std::uint32_t>(stages.size()),
                                                       .pStages = stages.data(),
                                                       .pVertexInputState = &vertex_input,
                                                       .pInputAssemblyState = &assembly,
                                                       .pViewportState = &viewport,
                                                       .pRasterizationState = &raster,
                                                       .pMultisampleState = &multisample,
                                                       .pDepthStencilState = &depth,
                                                       .pColorBlendState = &blend,
                                                       .pDynamicState = &dynamic,
                                                       .layout = d.layout,
                                                       .renderPass = d.target.render_pass,
                                                       .subpass = d.target.subpass}),
                 std::format("the graphics pipeline {} ({} / {})", describe(d.label), d.vertex, d.fragment));
    return std::move(pipeline);
}

Result<vk::raii::Pipeline> make_compute(const vk::raii::Device& device, const vk::raii::PipelineCache& cache,
                                        const PipelineLabel& label, vk::ShaderModule module, const char* entry,
                                        vk::PipelineLayout layout) {
    ATLAS_VK_LET(pipeline,
                 device.createComputePipeline(
                     cache, {.stage = {.stage = vk::ShaderStageFlagBits::eCompute, .module = module, .pName = entry},
                             .layout = layout}),
                 std::format("the compute pipeline {} ({})", describe(label), entry));
    return std::move(pipeline);
}

} // namespace atlas::gpu
