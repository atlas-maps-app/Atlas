// Windows for the session's host tests (standin/Session.hpp): what the phone's Bridge hands the session from a
// SurfaceView, made of what a host has. Offscreen images stand in for the swapchain: without a surface, so the colour
// stage's 10 bits are the device's to give (lavapipe draws rgb10a2 offscreen), or on a headless surface where the host
// has one (VK_EXT_headless_surface), so its queries are the phone's and, where the host's WSI makes one (the Linux
// runners' lavapipe), the swapchain itself. Hosts only: the Android build has no offscreen output.
#pragma once

#if !defined(__ANDROID__)

#include "gpu/Offscreen.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/Session.hpp"

#include <memory>

namespace atlas::test {

/// Offscreen images, as `Offscreen::create` makes them (the old output, if any, is the session's to let go of once this
/// returns, as a swapchain's oldSwapchain is); the next `refuse` of them fail as a driver's refused swapchain does, and
/// only 10-bit ones where `ten_only` (a driver that lists a 10-bit format it cannot make a swapchain of).
inline gpu::Result<std::unique_ptr<gpu::Output>>
offscreen_output(const gpu::Device& device, vk::Extent2D wanted, gpu::Depth depth, int& refuse, bool ten_only = false) {
    const bool ten = depth == gpu::Depth::deepest && gpu::colour_offscreen(device.facts(), depth).ten_bit();
    if (refuse > 0 && (ten || !ten_only)) {
        --refuse;
        return gpu::fail(ten ? "planted: the driver refused the 10-bit swapchain"
                             : "planted: the driver refused the swapchain");
    }
    ATLAS_GPU_LET(images, gpu::Offscreen::create(device, wanted, 3, depth));
    return std::unique_ptr<gpu::Output>(std::move(images));
}

/// A window of offscreen images, without a surface.
class OffscreenWindow final : public standin::SessionWindow {
public:
    /// The next `refuse` outputs fail (a driver refusing the swapchain): 10-bit ones only where `ten_only`.
    explicit OffscreenWindow(int refuse = 0, bool ten_only = false) : refuse_(refuse), ten_only_(ten_only) {}

    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& /*instance*/) override {
        return vk::raii::SurfaceKHR{nullptr};
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device,
                                                                   vk::SurfaceKHR /*surface*/, vk::Extent2D wanted,
                                                                   const gpu::Output* /*old*/,
                                                                   gpu::Depth depth) override {
        auto made = offscreen_output(device, wanted, depth, refuse_, ten_only_);
        if (made) ++made_;
        return made;
    }
    /// Refuses the next `n` outputs from now on (10-bit ones only where the window was made so).
    void refuse(int n) noexcept { refuse_ = n; }
    /// How many outputs it made (the swapchains a phone's window would have had made).
    [[nodiscard]] int made() const noexcept { return made_; }

private:
    int refuse_ = 0;
    bool ten_only_ = false;
    int made_ = 0;
};

/// A window on a headless surface: the surface and its queries are real; its output a swapchain where `swapchains`,
/// else offscreen images (this Mac's lavapipe aborts in Mesa's headless WSI making a swapchain).
class HeadlessWindow final : public standin::SessionWindow {
public:
    explicit HeadlessWindow(bool swapchains) : swapchains_(swapchains) {}

    [[nodiscard]] gpu::Result<vk::raii::SurfaceKHR> surface(const gpu::Instance& instance) override {
        return gpu::make_headless_surface(instance);
    }
    [[nodiscard]] gpu::Result<std::unique_ptr<gpu::Output>> output(const gpu::Device& device, vk::SurfaceKHR surface,
                                                                   vk::Extent2D wanted, const gpu::Output* old,
                                                                   gpu::Depth depth) override {
        if (swapchains_) return SessionWindow::output(device, surface, wanted, old, depth);
        int refuse = 0;
        return offscreen_output(device, wanted, depth, refuse);
    }

private:
    bool swapchains_ = false;
};

/// Whether this host's WSI makes swapchains on a headless surface (not this Mac's lavapipe:
/// tests/gpu/swapchain_vk_test).
[[nodiscard]] inline bool headless_swapchains() noexcept {
#if defined(__APPLE__)
    return false;
#else
    return true;
#endif
}

} // namespace atlas::test

#endif
