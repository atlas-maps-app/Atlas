// The surface and the swapchain (plan-vector D5.1, D5.2): R8G8B8A8_UNORM (else B8G8R8A8), the frame's colour in this
// alpha; or, where VK bench's colour stage asks, 10 bits a channel where the surface offers them and the device can
// draw the frame in them (rgb10a2, else bgr10a2; 8 bits again where the driver then refuses the swapchain); always in
// sRGB-nonlinear (blending on encoded values, as the renders composite; gpu/Facts.hpp's colour), FIFO, three images or
// the surface's minimum, opaque, the surface's current transform applied by the frame itself (pre-rotation), and a
// copy source where the surface allows it (the frosted backdrops).
// One render-finished semaphore per image; with VK_EXT_swapchain_maintenance1 a fence per present, so a retired
// swapchain is destroyed once its presents are done; with VK_GOOGLE_display_timing each present asks for the vsync's
// presentation time and the past presentations' real times are read back for VK bench.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <vector>

struct ANativeWindow;

namespace atlas::gpu {

/// A Vulkan surface on an Android window.
[[nodiscard]] Result<vk::raii::SurfaceKHR> make_android_surface(const Instance& instance, ANativeWindow* window);

/// A headless surface (VK_EXT_headless_surface): the host tests present through the real swapchain code with it.
[[nodiscard]] Result<vk::raii::SurfaceKHR> make_headless_surface(const Instance& instance);

/// What a presentation really did (VK_GOOGLE_display_timing).
struct PresentedTiming {
    std::uint32_t present_id = 0;
    std::uint64_t desired_ns = 0;
    std::uint64_t actual_ns = 0;
    std::uint64_t earliest_ns = 0;
    std::uint64_t margin_ns = 0;
};

/// The swapchain on a surface.
class Swapchain final : public Output {
public:
    /// Makes a swapchain of `wanted` pixels (used where the surface leaves the size to the swapchain), retiring `old`,
    /// in the frame's colour at `depth` (8 bits by default; as deep as the surface and the device allow in VK bench's
    /// colour stage).
    [[nodiscard]] static Result<std::unique_ptr<Swapchain>> create(const Device& device, vk::SurfaceKHR surface,
                                                                   vk::Extent2D wanted, std::unique_ptr<Swapchain> old,
                                                                   Depth depth = default_depth);
    Swapchain(const Swapchain&) = delete;
    Swapchain& operator=(const Swapchain&) = delete;
    Swapchain(Swapchain&&) = delete;
    Swapchain& operator=(Swapchain&&) = delete;
    ~Swapchain() override;

    [[nodiscard]] vk::Extent2D extent() const noexcept override { return extent_; }
    [[nodiscard]] vk::Format format() const noexcept override { return format_; }
    [[nodiscard]] std::span<const vk::Image> images() const noexcept override { return images_; }
    [[nodiscard]] std::span<const vk::ImageView> views() const noexcept override { return view_handles_; }
    [[nodiscard]] vk::ImageLayout final_layout() const noexcept override { return vk::ImageLayout::ePresentSrcKHR; }
    [[nodiscard]] bool transfer_source() const noexcept override { return transfer_source_; }
    [[nodiscard]] vk::SurfaceTransformFlagBitsKHR transform() const noexcept override { return transform_; }
    [[nodiscard]] Acquired acquire(vk::Semaphore acquired, std::uint32_t& index) override;
    [[nodiscard]] vk::Semaphore rendered(std::uint32_t index) const noexcept override { return *rendered_[index]; }
    [[nodiscard]] Acquired present(std::uint32_t index, std::uint64_t frame, std::uint64_t desired_ns) override;

    /// The frame's colour on this swapchain, and why (gpu/Facts.hpp).
    [[nodiscard]] const Colour& colour() const noexcept { return colour_; }
    /// The display's refresh period where the device says (VK_GOOGLE_display_timing), else 0.
    [[nodiscard]] std::uint64_t refresh_ns() const noexcept { return refresh_ns_; }
    /// The presentations completed since the last call, oldest first (VK_GOOGLE_display_timing; empty otherwise).
    void past_timings(std::vector<PresentedTiming>& out);
    /// Whether the swapchain should be made again (suboptimal or out of date).
    [[nodiscard]] bool stale() const noexcept { return stale_; }

private:
    Swapchain() = default;
    void retire_presents();

    const Device* device_ = nullptr;
    vk::raii::SwapchainKHR swapchain_{nullptr};
    std::vector<vk::Image> images_;
    std::vector<vk::raii::ImageView> views_;
    std::vector<vk::ImageView> view_handles_;
    std::vector<vk::raii::Semaphore> rendered_;
    std::vector<vk::raii::Fence> present_fences_; // per image, with swapchain_maintenance1 (else empty fences)
    std::vector<bool> fence_pending_;
    vk::Extent2D extent_;
    vk::Format format_ = vk::Format::eUndefined;
    Colour colour_;
    vk::SurfaceTransformFlagBitsKHR transform_ = vk::SurfaceTransformFlagBitsKHR::eIdentity;
    std::uint64_t refresh_ns_ = 0;
    bool transfer_source_ = false;
    bool stale_ = false;
};

} // namespace atlas::gpu
