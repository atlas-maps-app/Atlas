// Pipelines (plan-vector D5.6): immutable graphics and compute pipelines from the embedded SPIR-V, made through the
// pipeline cache, for either frame route. A pipeline's target is a subpass of a render pass, or a dynamic rendering
// scope with its formats and, for the composite, the local-read mapping of colour, depth and stencil to input
// attachment indices. Viewport and scissor are dynamic; everything else is fixed at creation.
#pragma once

#include "gpu/Error.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace atlas::gpu {

/// Where a graphics pipeline draws.
struct PipelineTarget {
    /// A render pass and its subpass, or a null render pass for dynamic rendering.
    vk::RenderPass render_pass;
    std::uint32_t subpass = 0;
    vk::SampleCountFlagBits samples = vk::SampleCountFlagBits::e1;
    /// Dynamic rendering: the colour formats, in attachment order, and the depth and stencil formats.
    std::vector<vk::Format> colour_formats;
    vk::Format depth_format = vk::Format::eUndefined;
    vk::Format stencil_format = vk::Format::eUndefined;
    /// Dynamic rendering with local reads: each colour attachment's input attachment index (VK_ATTACHMENT_UNUSED for
    /// none), and the depth's and the stencil's (empty optional: none). An empty list keeps Vulkan's defaults.
    std::vector<std::uint32_t> colour_input_indices;
    std::optional<std::uint32_t> depth_input_index;
    std::optional<std::uint32_t> stencil_input_index;
    bool local_read = false;
    /// How many colour attachments the subpass or scope has (for the blend states).
    std::uint32_t colour_count = 1;
};

/// One colour attachment's blending.
struct Blend {
    bool enable = false;
    vk::BlendFactor src_colour = vk::BlendFactor::eOne;
    vk::BlendFactor dst_colour = vk::BlendFactor::eZero;
    vk::BlendFactor src_alpha = vk::BlendFactor::eOne;
    vk::BlendFactor dst_alpha = vk::BlendFactor::eZero;
    vk::ColorComponentFlags mask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                   vk::ColorComponentFlagBits::eB | vk::ColorComponentFlagBits::eA;

    /// Writes the fragment as it is.
    [[nodiscard]] static Blend opaque() noexcept { return {}; }
    /// Source over, premultiplied: the renders' compositing of translucent paint.
    [[nodiscard]] static Blend over() noexcept {
        return {.enable = true,
                .src_colour = vk::BlendFactor::eOne,
                .dst_colour = vk::BlendFactor::eOneMinusSrcAlpha,
                .src_alpha = vk::BlendFactor::eOne,
                .dst_alpha = vk::BlendFactor::eOneMinusSrcAlpha};
    }
    /// `mix-blend-mode: screen` on premultiplied colour: s + d - s·d (D5.4).
    [[nodiscard]] static Blend screen() noexcept {
        return {.enable = true,
                .src_colour = vk::BlendFactor::eOne,
                .dst_colour = vk::BlendFactor::eOneMinusSrcColor,
                .src_alpha = vk::BlendFactor::eOne,
                .dst_alpha = vk::BlendFactor::eOneMinusSrcAlpha};
    }
    /// Leaves the attachment untouched (an attachment this pipeline does not write).
    [[nodiscard]] static Blend untouched() noexcept { return {.mask = {}}; }
};

/// A graphics pipeline.
struct GraphicsDesc {
    vk::ShaderModule module;
    const char* vertex = nullptr;
    const char* fragment = nullptr;
    /// The fragment stage's module when it is not `module` (a GLSL-route module, whose entry point is "main").
    vk::ShaderModule fragment_module;
    const vk::SpecializationInfo* specialization = nullptr; // for both stages
    vk::PipelineLayout layout;
    PipelineTarget target;
    vk::PrimitiveTopology topology = vk::PrimitiveTopology::eTriangleList;
    vk::CullModeFlags cull = vk::CullModeFlagBits::eNone;
    vk::FrontFace front = vk::FrontFace::eCounterClockwise;
    bool alpha_to_coverage = false;
    bool depth_test = false;
    bool depth_write = false;
    vk::CompareOp depth_op = vk::CompareOp::eLess;
    bool stencil_test = false;
    vk::StencilOpState stencil{};
    std::array<Blend, 4> blends{};
};

[[nodiscard]] Result<vk::raii::ShaderModule> make_module(const vk::raii::Device& device,
                                                         std::span<const std::uint32_t> spirv);

[[nodiscard]] Result<vk::raii::PipelineLayout> make_layout(const vk::raii::Device& device,
                                                           std::span<const vk::DescriptorSetLayout> sets,
                                                           std::uint32_t push_bytes, vk::ShaderStageFlags push_stages);

[[nodiscard]] Result<vk::raii::Pipeline> make_graphics(const vk::raii::Device& device,
                                                       const vk::raii::PipelineCache& cache, const GraphicsDesc& desc);

[[nodiscard]] Result<vk::raii::Pipeline> make_compute(const vk::raii::Device& device,
                                                      const vk::raii::PipelineCache& cache, vk::ShaderModule module,
                                                      const char* entry, vk::PipelineLayout layout,
                                                      const vk::SpecializationInfo* specialization = nullptr);

} // namespace atlas::gpu
