// The composite (plan-vector D5.4, K7): the main pass's second subpass, one full-screen triangle at pixel rate that
// reads every sample of its pixel from tile memory, makes the pixel's colour from them and applies the screen steps
// once, in the renders' order (shaders/shared/composite.slang, which the twin runs as C++: composite_pixel below). It
// draws the haze and the vignette; `mat`'s coverage rule at the objects group's silhouettes and the grain (K17a), the
// cloud shadows, the rain and the wash (K17) are stubbed, and with every step off the composite passes the colours
// through bit for bit (CP/tests/frame/composite_vk_test.cpp).
//
// In both frame routes (the render pass's second subpass, or the local reads inside one dynamic rendering scope) and
// every configuration (4x, 2x and 1x samples: a module each, and Slang's GLSL route where the device may not take
// Slang's own SPIR-V for subpass loads). Set 2 holds its input attachments: the colour samples (binding 0) and their
// stencil (binding 1, for K17a's coverage rule).
#pragma once

#include "frame/FrameBlock.hpp"
#include "frame/Pass.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace atlas::passes {

/// The composite pass.
class Composite final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "composite"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    /// Whether the composite runs through Slang's GLSL route.
    [[nodiscard]] bool glsl_route() const noexcept { return glsl_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{frame::Phase{.slot = frame::Slot::composite}};
    vk::raii::DescriptorSetLayout inputs_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::DescriptorSet inputs_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::ShaderModule glsl_module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
    bool glsl_ = false;
};

/// The composite of one pixel as the GPU computes it (the shader's own text, as C++): the levels of its `n` samples'
/// colours (`levels`: 255, or 1023 at 10 bits), the mean of their levels, the steps `layers` runs at CSS point `p` of a
/// `frame`-sized frame; the finished colour as whole levels.
[[nodiscard]] hlsl::float3 composite_pixel(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples,
                                           hlsl::float2 p, hlsl::float2 frame) noexcept;

} // namespace atlas::passes
