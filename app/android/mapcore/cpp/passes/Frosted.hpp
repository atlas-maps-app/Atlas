// The frosted backdrops (plan-vector D5.1, D5.12's pass 8, K11b). On the SurfaceView host the map's window lies under
// the app's, so HWUI's frosted glass cannot read it: the core draws the glass itself. After the main pass, the frame
// under each frosted shape of the chrome (the camera state's keep-out shapes, from Compose's layout through
// MV/vk/ChromeShapes.kt) is copied at a quarter of its size, blurred by WebKit's three boxes per axis for the shape's
// σ (fx/Blur.hpp; Backdrop.kt blurs HWUI's quarter-size copy by the Gaussian they approximate), and drawn back inside
// the shape's rounded rectangle, saturated as CSS's saturate() is (Filter Effects' matrix on the unpremultiplied
// colour, clamped), at the alpha of the chrome over it (the shape's fade: a screen fading out takes its glass with it,
// whether or not the SurfaceView follows the views' alpha), in short render passes limited to the shapes' boxes.
// Compose draws only the tint and the content over it (APP/ui/kit/Backdrop.kt).
//
// The copy lies on the output's own grid of blocks, 4 × 4 pixels from σ = 8 px as Backdrop.kt's DOWN (1 × 1 below,
// coarser past σ = 64 px), so that a pill moving over a still map blurs the same texels and its glass does not
// shimmer; each block is the linear blit's mean of its middle four pixels, as HWUI's scaled draw of the frame samples
// it. A shape's region of the copy is its box, clipped to the frame, grown by the boxes' reach; regions of one σ that
// overlap are merged, and every region is packed into one copy image (a coarser block where it would not fit). Past
// the frame's edge the boxes repeat its edge (vImage's edge extend), as CSS's backdrop blur keeps its glass opaque at
// the viewport's edge and Backdrop.kt's glass, clipped to the window, clamps there.
//
// Per frame, when a shape is frosted: the output to TRANSFER_SRC, one blit of every region, the boxes along x into a
// scratch image and along y back into the copy (one dispatch each over every region), the output to
// COLOR_ATTACHMENT, one render pass per group of nearby shapes over the output (its render area the group's box), and
// the output back to its final layout. The stand-in's pills (standin/StandIn.cpp) draw through the same code, so VK
// bench's GPU time after the main pass is this pass's on the phone.
#pragma once

#include "base/arena.hpp"
#include "frame/Counters.hpp"
#include "frame/Pass.hpp"
#include "fx/Blur.hpp"
#include "gpu/Frames.hpp"
#include "style/CameraState.hpp"

#include <array>
#include <chrono>
#include <cstdint>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::passes {

/// One frosted shape of the frame, in the output's pixels (its pre-rotation applied).
struct FrostShape {
    double left = 0;
    double top = 0;
    double right = 0;
    double bottom = 0;
    double radius = 0;        ///< the corner radius, px
    double sigma = 0;         ///< the blur's deviation, px
    double saturation = 1;    ///< CSS saturate()'s amount
    double alpha = 1;         ///< the chrome's alpha over it (a fading screen's): what its glass is drawn at
    std::uint32_t down = 4;   ///< the copy's block for it, px
    std::uint32_t region = 0; ///< its region of the copy
    std::uint32_t group = 0;  ///< its render pass
};

/// One region of the copy: a rectangle of blocks of the output's grid, and its place in the copy image.
struct FrostRegion {
    std::uint32_t down = 4;   ///< a block's side, px
    std::int32_t gx = 0;      ///< its first block on the output's grid
    std::int32_t gy = 0;      ///<
    std::uint32_t width = 0;  ///< in blocks
    std::uint32_t height = 0; ///<
    std::uint32_t cx = 0;     ///< its place in the copy, texels
    std::uint32_t cy = 0;     ///<
    fx::Run run;              ///< the boxes, the same along both axes
    std::uint32_t shapes = 0; ///< how many shapes it holds
};

/// One render pass of the glass: the output's pixels [x0, x1) × [y0, y1).
struct FrostGroup {
    std::int32_t x0 = 0;
    std::int32_t y0 = 0;
    std::int32_t x1 = 0;
    std::int32_t y1 = 0;
};

/// What a frame frosts.
struct FrostPlan {
    InplaceVector<FrostShape, style::max_keep_outs> shapes;
    InplaceVector<FrostRegion, style::max_keep_outs> regions;
    InplaceVector<FrostGroup, style::max_keep_outs> groups;

    [[nodiscard]] bool empty() const noexcept { return shapes.empty(); }
};

/// The copy's block for a blur of `sigma` px: 1 below 8 px (Backdrop.kt's full resolution), else 4, doubled while
/// the blur would span more than 16 blocks (at most 64).
[[nodiscard]] std::uint32_t frost_block(double sigma) noexcept;

/// Plans the frosted shapes of `camera` over an output of `output` px turned by `quarter_turns` (the surface's
/// pre-rotation) into regions of a copy of `copy` texels and groups of the glass's render passes. Shapes that do not
/// touch the frame, those with no area and those whose chrome has faded out entirely are left out. A pure function of
/// its arguments, allocating nothing.
void plan_frost(const style::CameraState& camera, std::uint32_t quarter_turns, vk::Extent2D output, vk::Extent2D copy,
                FrostPlan& plan) noexcept;

/// The copy image's size for an output: a quarter of it in each direction, half as tall again, and a margin.
[[nodiscard]] vk::Extent2D frost_copy_extent(vk::Extent2D output) noexcept;

/// The frosted backdrops of one output: what the pass and the stand-in record.
class FrostedBackdrops {
public:
    /// Where the backdrops are drawn, and what their pipelines go through.
    struct Target {
        const gpu::Device& device;
        const gpu::PipelineCache& cache;
        gpu::PipelineWatch* watch = nullptr;
        const gpu::Output& output;
        /// How the log and the crash guard name the frame's configuration.
        std::string_view configuration;
        /// Where the driver's time and the count of pipelines made are added (may be null).
        frame::PipelineStats* stats = nullptr;
    };

    FrostedBackdrops() = default;
    FrostedBackdrops(const FrostedBackdrops&) = delete;
    FrostedBackdrops& operator=(const FrostedBackdrops&) = delete;
    FrostedBackdrops(FrostedBackdrops&&) = delete;
    FrostedBackdrops& operator=(FrostedBackdrops&&) = delete;
    ~FrostedBackdrops() = default;

    /// Makes the copy, the scratch image, the jobs, the boxes' and the glass's pipelines and the glass's render pass
    /// and framebuffers for `target`'s output; what an earlier prepare made goes first. An output that cannot be
    /// copied from (no TRANSFER_SRC on its images) gets nothing, and the pass draws nothing on it (said once).
    [[nodiscard]] gpu::Result<void> prepare(const Target& target);
    /// Lets go of everything prepare made (the GPU has finished every frame that used it).
    void release() noexcept;

    /// Records the frosted backdrops of `camera`'s frosted shapes over output image `image`, after the main pass left
    /// it in the output's final layout, and leaves it in that layout again; frame slot `slot`'s jobs. Nothing when no
    /// shape is frosted, or prepare made nothing.
    void record(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync, std::uint32_t image, std::uint32_t slot,
                const style::CameraState& camera, frame::PassCounters& counters);

    /// Whether prepare made what record needs.
    [[nodiscard]] bool ready() const noexcept { return ready_; }
    /// Whether it holds anything prepare made.
    [[nodiscard]] bool holds_prepared() const noexcept;
    /// The last frame's plan.
    [[nodiscard]] const FrostPlan& plan() const noexcept { return plan_; }
    /// The copy's size, texels.
    [[nodiscard]] vk::Extent2D copy_extent() const noexcept { return copy_extent_; }
    /// The bytes of the images and buffers it holds.
    [[nodiscard]] vk::DeviceSize bytes() const noexcept;

private:
    void log_plan(const style::CameraState& camera);

    const gpu::Device* device_ = nullptr;
    vk::Extent2D extent_{};
    vk::Extent2D copy_extent_{};
    vk::ImageLayout final_layout_ = vk::ImageLayout::ePresentSrcKHR;
    std::uint32_t quarter_turns_ = 0;
    std::vector<vk::Image> images_;
    bool ready_ = false;

    gpu::Image copy_;
    gpu::Image scratch_;
    vk::raii::ImageView copy_view_{nullptr};
    vk::raii::ImageView scratch_view_{nullptr};
    vk::raii::Sampler sampler_{nullptr};
    gpu::Buffer jobs_;
    std::unique_ptr<fx::BoxBlur> boxes_;
    vk::raii::DescriptorSet along_x_{nullptr}; // copy → scratch
    vk::raii::DescriptorSet along_y_{nullptr}; // scratch → copy

    vk::raii::DescriptorSetLayout glass_set_layout_{nullptr};
    vk::raii::DescriptorPool glass_pool_{nullptr};
    vk::raii::DescriptorSet glass_set_{nullptr};
    vk::raii::PipelineLayout glass_layout_{nullptr};
    vk::raii::ShaderModule glass_module_{nullptr};
    vk::raii::RenderPass glass_pass_{nullptr};
    std::vector<vk::raii::Framebuffer> framebuffers_;
    vk::raii::Pipeline glass_pipeline_{nullptr};

    FrostPlan plan_;
    std::array<fx::BlurRect, fx::BoxBlur::max_rects> rects_{};
    // What the log said last: the shapes it named and when (a line when they change, at most one a second).
    FrostPlan logged_;
    std::chrono::steady_clock::time_point logged_at_;
    bool logged_any_ = false;
};

/// The frosted backdrops as a pass of the frame: its one phase follows the main pass (Slot::after).
class Frosted final : public frame::Pass {
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "frosted"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    [[nodiscard]] const FrostedBackdrops& backdrops() const noexcept { return backdrops_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{frame::Phase{.slot = frame::Slot::after}};
    FrostedBackdrops backdrops_;
};

} // namespace atlas::passes
