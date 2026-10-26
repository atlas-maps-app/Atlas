// The composite (plan-vector D5.4, K7): the main pass's second subpass, one full-screen triangle at pixel rate that
// reads every sample of its pixel from tile memory, makes the pixel's colour from them and applies the screen steps
// once, in the renders' order (shaders/shared/composite.slang, which the twin runs as C++: composite_pixel below). It
// draws the haze and the vignette (K7), `mat`'s coverage rule at the objects group's silhouettes and the grain (K17a:
// each sample's stencil and the grain field's bytes, CP/tests/fx/composite_fx_vk_test.cpp); the cloud shadows, the
// rain and the wash (K17) are stubbed, and with every step off the composite passes the colours through bit for bit
// (CP/tests/frame/composite_vk_test.cpp).
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
#include <vector>

namespace atlas::passes {

/// The composite pass.
class Composite final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "composite"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    /// The frame's tables (ENGINE.md gain 2), into the frame slot's slice: the haze's opacity by upright row and the
    /// vignette's squared distances by upright column and row, by shared/composite.slang's own arithmetic, so the
    /// shader reads what its arithmetic would give at each pixel.
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;
    /// The twin: the screen steps it draws as the renders' SVG writes them (the haze's gradient and rect,
    /// ScreenLayers.hazeDef and hazeRect; fin's vignette, its `vig` gradient and rect), after every object.
    void emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const override;

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
    gpu::Buffer tables_;               // a slice per frame slot: 3 · side_ words (set 2 binding 2)
    std::vector<std::uint32_t> words_; // the frame's tables before they are copied into its slice
    std::uint32_t side_ = 0;           // each table's length: the output's longer side
    vk::DeviceSize slice_ = 0;         // a slice's bytes, aligned for its dynamic offset
    bool glsl_ = false;
};

/// What the composite reads of a pixel beside its samples' colours, for K17a's steps: which samples are the objects
/// group's (the stencil's objects bit; sample s at bit s), whether mat runs (shaders/fx/mat.slang's mat_on), and the
/// grain field's grey and alpha there with fin's opacity byte (shared/composite.slang's COMPOSITE_GRAIN_DAY or _NIGHT;
/// all 0 where the grain does not run: the fields not bound).
struct CompositeInputs {
    std::uint32_t objects = 0;
    bool mat = false;
    hlsl::uint3 grain{0U, 0U, 0U};
};

/// The composite of one pixel as the GPU computes it (the shader's own text, as C++): the levels of its `n` samples'
/// colours (`levels`: 255, or 1023 at 10 bits), the pixel's colour from them (the mean of their levels, or mat's rule
/// where the objects group covers some of them and mat runs), the steps `layers` runs at the upright device pixel
/// `pixel` of the W × H CSS frame at its density (`frame`: W, H, density; the grain with `inputs`' bytes); the finished
/// colour as whole levels.
[[nodiscard]] hlsl::float3 composite_pixel_fx(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples,
                                              const CompositeInputs& inputs, hlsl::uint2 pixel,
                                              hlsl::float3 frame) noexcept;
/// composite_pixel_fx at density 1 (`p`, the CSS point of the pixel's centre, is in device px), with no sample of the
/// objects group and no grain.
[[nodiscard]] hlsl::float3 composite_pixel(const shaders::FrameLayers& layers, std::span<const hlsl::float3> samples,
                                           hlsl::float2 p, hlsl::float2 frame) noexcept;

} // namespace atlas::passes
