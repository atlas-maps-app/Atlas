// VK bench's stand-in load (plan-vector K1, A0): a city-sized frame drawn through the real device layer, so the owner's
// phone measures what the engine's frame will cost before the renderer exists. About 5 FSE at S1 and 0.5 M prism
// triangles in the main pass, in every frame configuration, frame form and route:
//   offscreen: shadow footprints of the boxes within 1.2 km into an R8 mask at half the size, blurred by six compute
//     box blurs; the canal's mirrored waterside into an RGBA8 box at the configuration's samples, resolved; 64 crowns a
//     frame into a 2048² sprite atlas;
//   the main pass: 50,176 boxes front to back with rank depth and the objects bit (256 draws), 2,048 tree sprites by
//     alpha-to-coverage, the ground with its roads, shadows and rippled water, 64 screen-blended glows; then the
//     composite (the coverage rule at silhouettes, the haze, the vignette), or in one subpass the same effects per
//     fragment;
//   after it, on the SurfaceView host: the frosted pills, from a quarter-size blurred copy of the frame.
#pragma once

#include "gpu/Descriptors.hpp"
#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Memory.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"
#include "standin/City.hpp"
#include "standin/View.hpp"
#include "style/CameraState.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace atlas::standin {

/// What one frame of the stand-in issued, and what it cost the CPU.
struct Counters {
    std::uint32_t draws = 0;
    std::uint32_t dispatches = 0;
    std::uint64_t triangles = 0;
    std::uint64_t instances = 0;
    std::uint64_t swaps = 0; // the painter's order's insertion pass
    double ranks_ms = 0;     // ranks, casters and the frame's buffers
    double record_ms = 0;    // recording the commands
};

/// The stand-in.
class StandIn final : public gpu::Content {
public:
    [[nodiscard]] static gpu::Result<std::unique_ptr<StandIn>>
    create(const gpu::Device& device, const gpu::PipelineCache& cache, std::shared_ptr<const City> city);
    ~StandIn() override;

    /// The camera of the next frame (the gesture camera, as FrameSource wrote it).
    void set_camera(const style::CameraState& camera) noexcept { camera_ = camera; }
    /// Seconds since the bench began (the water's ripples).
    void set_time(double seconds) noexcept { seconds_ = seconds; }
    /// Draws the frosted backdrops of the camera's frosted keep-out shapes (the SurfaceView host).
    void set_pills(bool on) noexcept { pills_ = on; }

    [[nodiscard]] gpu::Result<void> prepare(const gpu::MainPass& pass, const gpu::Output& output) override;
    void record(const gpu::FrameContext& frame) override;

    [[nodiscard]] const Counters& counters() const noexcept { return counters_; }
    /// The milliseconds spent creating the pipelines the last prepare needs (the fixed ones made at create, and the
    /// configuration's), and how many they are.
    [[nodiscard]] double pipelines_ms() const noexcept { return pipelines_ms_; }
    [[nodiscard]] std::uint32_t pipeline_count() const noexcept { return pipeline_count_; }
    /// Whether the composite runs through Slang's GLSL route (the device may not take Slang's own SPIR-V for it).
    [[nodiscard]] bool composite_glsl() const noexcept { return composite_glsl_; }
    /// The bytes of the stand-in's own images and buffers (the attachments of the main pass excepted).
    [[nodiscard]] vk::DeviceSize bytes() const noexcept;

private:
    StandIn(const gpu::Device& device, const gpu::PipelineCache& cache, std::shared_ptr<const City> city);
    [[nodiscard]] gpu::Result<void> create_fixed();
    [[nodiscard]] gpu::Result<void> prepare_offscreen(const gpu::MainPass& pass, const gpu::Output& output);
    [[nodiscard]] gpu::Result<void> prepare_pipelines(const gpu::MainPass& pass, const gpu::Output& output);
    void write_frame(std::uint32_t slot);
    void record_offscreen(const gpu::FrameContext& frame, std::uint32_t slot);
    void record_main(const gpu::FrameContext& frame, std::uint32_t slot);
    void record_pills(const gpu::FrameContext& frame);
    void blur(const vk::raii::CommandBuffer& cmd, std::span<const vk::DescriptorSet> sets, vk::Extent2D size,
              int radius, vk::Image first_target, vk::Image second_target);

    const gpu::Device& device_;
    const gpu::PipelineCache& cache_;
    std::shared_ptr<const City> city_;
    std::unique_ptr<Ranks> ranks_;
    style::CameraState camera_{};
    double seconds_ = 0;
    bool pills_ = true;
    Counters counters_;
    double fixed_pipelines_ms_ = 0; // the atlas's, the shadow's and the blur's, made once
    double pipelines_ms_ = 0;       // those and the configuration's, as last prepared
    std::uint32_t pipeline_count_ = 0;
    bool composite_glsl_ = false;
    std::vector<std::uint32_t> casters_;
    std::uint32_t shadow_casters_ = 0; // the first casters_ cast shadows; the rest are the waterside

    // Fixed: layouts, the sampler, the city's buffers, the per-frame ring, the atlas.
    vk::raii::DescriptorSetLayout frame_layout_{nullptr}, scene_layout_{nullptr}, haze_layout_{nullptr},
        inputs_layout_{nullptr}, blur_layout_{nullptr}, frost_layout_{nullptr};
    vk::raii::PipelineLayout scene_pipeline_layout_{nullptr}, composite_pipeline_layout_{nullptr},
        blur_pipeline_layout_{nullptr}, frost_pipeline_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::DescriptorSet frame_set_{nullptr}, scene_set_{nullptr}, haze_set_{nullptr}, inputs_set_{nullptr};
    std::vector<vk::raii::DescriptorSet> blur_sets_; // mask→A, A→B, B→A, then C→D, D→C
    vk::raii::DescriptorSet frost_set_{nullptr};
    vk::raii::Sampler sampler_{nullptr};
    gpu::Buffer boxes_, trees_, ring_;
    vk::DeviceSize slot_bytes_ = 0;
    struct Offsets {
        vk::DeviceSize frame, haze, ranks, order, casters;
    } offsets_{};
    vk::raii::ShaderModule standin_module_{nullptr}, composite_module_{nullptr}, blur_module_{nullptr},
        frost_module_{nullptr};
    gpu::Image atlas_;
    vk::raii::ImageView atlas_view_{nullptr};
    vk::raii::RenderPass atlas_clear_pass_{nullptr}, atlas_load_pass_{nullptr}, shadow_pass_{nullptr};
    vk::raii::Framebuffer atlas_framebuffer_{nullptr};
    vk::raii::Pipeline atlas_pipeline_{nullptr}, shadow_pipeline_{nullptr}, blur_pipeline_{nullptr};
    bool atlas_ready_ = false;

    // Of the output's size and the configuration.
    vk::Extent2D extent_{};
    vk::Extent2D half_{};
    vk::Extent2D quarter_{};
    vk::Extent2D water_extent_{};
    gpu::Image mask_, blur_a_, blur_b_, frost_c_, frost_d_, water_, water_colour_, water_depth_;
    vk::raii::ImageView mask_view_{nullptr}, blur_a_view_{nullptr}, blur_b_view_{nullptr}, frost_c_view_{nullptr},
        frost_d_view_{nullptr}, water_view_{nullptr}, water_colour_view_{nullptr}, water_depth_view_{nullptr};
    vk::raii::Framebuffer shadow_framebuffer_{nullptr}, water_framebuffer_{nullptr};
    vk::raii::RenderPass water_pass_{nullptr}, frost_pass_{nullptr};
    std::vector<vk::raii::Framebuffer> frost_framebuffers_;
    vk::raii::Pipeline mirror_pipeline_{nullptr}, box_pipeline_{nullptr}, tree_pipeline_{nullptr},
        ground_pipeline_{nullptr}, glow_pipeline_{nullptr}, composite_pipeline_{nullptr}, frost_pipeline_{nullptr};
    bool frost_ready_ = false;
    vk::ImageLayout output_final_ = vk::ImageLayout::eUndefined;
    std::uint32_t quarter_turns_ = 0;
};

/// How many quarter turns a surface transform is (the pre-rotation the frame applies).
[[nodiscard]] std::uint32_t quarter_turns_of(vk::SurfaceTransformFlagBitsKHR transform) noexcept;

} // namespace atlas::standin
