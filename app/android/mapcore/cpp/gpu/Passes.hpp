// The main pass (plan-vector D5.3, D5.4) in both frame routes: a render pass with two subpasses (Vulkan 1.0's
// render pass objects through VK_KHR_create_renderpass2), or one dynamic rendering scope whose composite reads the
// samples locally (VK_KHR_dynamic_rendering_local_read). The scene subpass draws into the colour samples and the
// depth-stencil (reversed depth, the objects bit); the composite reads every sample of a pixel and its stencil and
// writes the finished colour, either into the samples, resolved into the output on chip, or straight into the 1x
// output. The one-subpass form (ONEPASS) draws the scene with its effects per fragment and resolves.
//
// Every configuration is the same few objects with other sample counts, memory and references: attachments 0 (colour
// samples), 1 (depth-stencil samples) and 2 (the output: a swapchain image, or an offscreen image on hosts).
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Memory.hpp"
#include "gpu/Pipelines.hpp"
#include "gpu/Sync.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace atlas::gpu {

/// The main pass's configuration and surroundings.
struct MainPassSpec {
    Configuration config;
    vk::Extent2D extent;
    /// The scene's samples (and at 1x the composite's input): the output's format (gpu/Facts.hpp's colour), which a
    /// resolve needs; 10 bits a channel where the frame's colour has them.
    vk::Format colour_format = vk::Format::eR8G8B8A8Unorm;
    vk::Format output_format = vk::Format::eR8G8B8A8Unorm;
    vk::Format depth_stencil = vk::Format::eD32SfloatS8Uint;
    /// The output's layout after the pass: PRESENT_SRC_KHR on a swapchain, TRANSFER_SRC_OPTIMAL for a readback.
    vk::ImageLayout output_final = vk::ImageLayout::ePresentSrcKHR;
};

/// The attachments and pass objects of one configuration at one size, for a set of output images.
class MainPass {
public:
    /// Makes the pass for `outputs` (views of the output images, in their order).
    [[nodiscard]] static Result<MainPass> create(const Device& device, const MainPassSpec& spec,
                                                 std::span<const vk::ImageView> outputs,
                                                 std::span<const vk::Image> output_images);

    MainPass(MainPass&&) = default;
    MainPass& operator=(MainPass&&) = default;
    MainPass(const MainPass&) = delete;
    MainPass& operator=(const MainPass&) = delete;
    ~MainPass() = default;

    [[nodiscard]] const MainPassSpec& spec() const noexcept { return spec_; }
    [[nodiscard]] bool composite() const noexcept { return spec_.config.form == FrameForm::composite; }
    /// Where the scene's pipelines draw.
    [[nodiscard]] const PipelineTarget& scene_target() const noexcept { return scene_; }
    /// Where the composite's pipeline draws (composite form only).
    [[nodiscard]] const PipelineTarget& composite_target() const noexcept { return composite_; }
    /// The colour samples and the stencil, as the composite's input attachment descriptors read them.
    [[nodiscard]] vk::ImageView colour_input() const noexcept { return *colour_view_; }
    [[nodiscard]] vk::ImageView stencil_input() const noexcept { return *stencil_view_; }
    [[nodiscard]] vk::ImageLayout colour_input_layout() const noexcept { return colour_input_layout_; }
    [[nodiscard]] vk::ImageLayout stencil_input_layout() const noexcept { return stencil_input_layout_; }
    /// The bytes the attachments reserved (0 in lazily allocated memory).
    [[nodiscard]] vk::DeviceSize attachment_bytes() const noexcept { return colour_.bytes() + depth_.bytes(); }

    /// Begins the pass on output image `index`, clearing colour to `clear`, depth to `clear_depth` (0, infinitely far,
    /// for the map's reversed depth, shaders/shared/painter.slang; 1 for VK bench's stand-in) and stencil to 0.
    void begin(const vk::raii::CommandBuffer& cmd, const Sync& sync, std::uint32_t index,
               const std::array<float, 4>& clear, float clear_depth = 0.0F) const;
    /// Moves to the composite: the next subpass, or the local-read barrier inside the rendering scope.
    void next(const vk::raii::CommandBuffer& cmd, const Sync& sync) const;
    /// Ends the pass; the output is in the spec's final layout.
    void end(const vk::raii::CommandBuffer& cmd, const Sync& sync, std::uint32_t index) const;

private:
    MainPass() = default;
    [[nodiscard]] Result<void> make_render_pass(const Device& device, std::span<const vk::ImageView> outputs);
    void make_dynamic_targets(const Device& device);

    MainPassSpec spec_;
    Image colour_;
    Image depth_;
    vk::raii::ImageView colour_view_{nullptr};
    vk::raii::ImageView depth_view_{nullptr};
    vk::raii::ImageView stencil_view_{nullptr};
    vk::raii::RenderPass render_pass_{nullptr}; // empty on the dynamic-rendering route
    std::vector<vk::raii::Framebuffer> framebuffers_;
    std::vector<vk::ImageView> outputs_;
    std::vector<vk::Image> output_images_;
    PipelineTarget scene_;
    PipelineTarget composite_;
    vk::ImageLayout colour_input_layout_ = vk::ImageLayout::eGeneral;
    vk::ImageLayout stencil_input_layout_ = vk::ImageLayout::eDepthStencilReadOnlyOptimal;
    bool one_sample_ = false; // the 1x configuration: the scene draws a 1x colour attachment, the composite the output
};

} // namespace atlas::gpu
