// The surface and the swapchain (gpu/Swapchain.hpp).
#include "gpu/Swapchain.hpp"

#include "base/log.hpp"

#include <algorithm>
#include <format>
#include <tuple>

namespace atlas::gpu {

namespace {

[[nodiscard]] bool quarter_turn(vk::SurfaceTransformFlagBitsKHR t) noexcept {
    using T = vk::SurfaceTransformFlagBitsKHR;
    return t == T::eRotate90 || t == T::eRotate270 || t == T::eHorizontalMirrorRotate90 ||
           t == T::eHorizontalMirrorRotate270;
}

[[nodiscard]] vk::SurfaceFormatKHR pick_format(const std::vector<vk::SurfaceFormatKHR>& formats) noexcept {
    for (const vk::Format wanted : {vk::Format::eR8G8B8A8Unorm, vk::Format::eB8G8R8A8Unorm}) {
        for (const vk::SurfaceFormatKHR& f : formats) {
            if (f.format == wanted && f.colorSpace == vk::ColorSpaceKHR::eSrgbNonlinear) return f;
        }
    }
    return formats.empty() ? vk::SurfaceFormatKHR{.format = vk::Format::eR8G8B8A8Unorm,
                                                  .colorSpace = vk::ColorSpaceKHR::eSrgbNonlinear}
                           : formats.front();
}

[[nodiscard]] vk::CompositeAlphaFlagBitsKHR pick_alpha(vk::CompositeAlphaFlagsKHR supported) noexcept {
    for (const auto a :
         {vk::CompositeAlphaFlagBitsKHR::eOpaque, vk::CompositeAlphaFlagBitsKHR::eInherit,
          vk::CompositeAlphaFlagBitsKHR::ePreMultiplied, vk::CompositeAlphaFlagBitsKHR::ePostMultiplied}) {
        if (supported & a) return a;
    }
    return vk::CompositeAlphaFlagBitsKHR::eOpaque;
}

} // namespace

Result<vk::raii::SurfaceKHR> make_android_surface(const Instance& instance, ANativeWindow* window) {
    if (!instance.has_extension(vk::KHRAndroidSurfaceExtensionName))
        return fail("the instance has no VK_KHR_android_surface");
    ATLAS_VK_LET(surface, instance.handle().createAndroidSurfaceKHR({.window = window}), "vkCreateAndroidSurfaceKHR");
    return std::move(surface);
}

Result<vk::raii::SurfaceKHR> make_headless_surface(const Instance& instance) {
    if (!instance.has_extension(vk::EXTHeadlessSurfaceExtensionName))
        return fail("the instance has no VK_EXT_headless_surface");
    ATLAS_VK_LET(surface, instance.handle().createHeadlessSurfaceEXT({}), "vkCreateHeadlessSurfaceEXT");
    return std::move(surface);
}

Swapchain::~Swapchain() {
    retire_presents();
    rendered_.clear();
    views_.clear();
    swapchain_.clear();
}

void Swapchain::retire_presents() {
    if (device_ == nullptr || !*swapchain_) return;
    if (device_->enabled().swapchain_maintenance1) {
        for (std::size_t i = 0; i < present_fences_.size(); ++i) {
            if (fence_pending_[i]) (void)device_->handle().waitForFences(*present_fences_[i], vk::True, UINT64_MAX);
            fence_pending_[i] = false;
        }
    } else {
        // Without present fences nothing says when the presentation engine let go of the images: wait for the queue
        // (a resize or a rotation, never in the frame loop).
        std::ignore = device_->queue().waitIdle(); // a lost device has nothing left to wait for
    }
}

Result<std::unique_ptr<Swapchain>> Swapchain::create(const Device& device, vk::SurfaceKHR surface, vk::Extent2D wanted,
                                                     std::unique_ptr<Swapchain> old) {
    if (!device.enabled().swapchain) {
        return fail(std::format("{} was opened for offscreen work, without VK_KHR_swapchain: it cannot present",
                                device.facts().name));
    }
    std::unique_ptr<Swapchain> self(new Swapchain()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    self->device_ = &device;
    const vk::raii::PhysicalDevice& physical = device.physical();
    ATLAS_VK_LET(caps, physical.getSurfaceCapabilitiesKHR(surface), "the surface's capabilities");
    ATLAS_VK_LET(formats, physical.getSurfaceFormatsKHR(surface), "the surface's formats");
    const vk::SurfaceFormatKHR format = pick_format(formats);
    self->format_ = format.format;

    vk::Extent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = std::clamp(wanted.width, caps.minImageExtent.width, caps.maxImageExtent.width);
        extent.height = std::clamp(wanted.height, caps.minImageExtent.height, caps.maxImageExtent.height);
    }
    // Pre-rotation (Android's Vulkan design notes): the images keep the display's natural orientation and the frame
    // rotates its own projection, so the compositor does not have to.
    self->transform_ = caps.currentTransform;
    if (quarter_turn(self->transform_)) std::swap(extent.width, extent.height);
    if (extent.width == 0 || extent.height == 0) return fail("the surface has no pixels");
    self->extent_ = extent;

    std::uint32_t count = std::max(caps.minImageCount, 3U);
    if (caps.maxImageCount != 0) count = std::min(count, caps.maxImageCount);
    vk::ImageUsageFlags usage = vk::ImageUsageFlagBits::eColorAttachment;
    if (caps.supportedUsageFlags & vk::ImageUsageFlagBits::eTransferSrc) {
        usage |= vk::ImageUsageFlagBits::eTransferSrc;
        self->transfer_source_ = true;
    }
    ATLAS_VK_LET(swapchain,
                 device.handle().createSwapchainKHR({.surface = surface,
                                                     .minImageCount = count,
                                                     .imageFormat = format.format,
                                                     .imageColorSpace = format.colorSpace,
                                                     .imageExtent = extent,
                                                     .imageArrayLayers = 1,
                                                     .imageUsage = usage,
                                                     .imageSharingMode = vk::SharingMode::eExclusive,
                                                     .preTransform = self->transform_,
                                                     .compositeAlpha = pick_alpha(caps.supportedCompositeAlpha),
                                                     .presentMode = vk::PresentModeKHR::eFifo,
                                                     .clipped = vk::True,
                                                     .oldSwapchain = old ? *old->swapchain_ : vk::SwapchainKHR{}}),
                 std::format("a swapchain of {} x {}", extent.width, extent.height));
    self->swapchain_ = std::move(swapchain);
    old.reset(); // retired once its presents are done (its destructor)

    ATLAS_VK_LET(images, self->swapchain_.getImages(), "the swapchain's images");
    self->images_ = std::move(images);
    for (const vk::Image image : self->images_) {
        ATLAS_VK_LET(
            view,
            device.handle().createImageView({.image = image,
                                             .viewType = vk::ImageViewType::e2D,
                                             .format = format.format,
                                             .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                                  .levelCount = 1,
                                                                  .layerCount = 1}}),
            "a swapchain image's view");
        self->view_handles_.push_back(*view);
        self->views_.push_back(std::move(view));
        ATLAS_VK_LET(semaphore, device.handle().createSemaphore({}), "a swapchain image's semaphore");
        self->rendered_.push_back(std::move(semaphore));
        if (device.enabled().swapchain_maintenance1) {
            ATLAS_VK_LET(fence, device.handle().createFence({}), "a present fence");
            self->present_fences_.emplace_back(std::move(fence));
        } else {
            self->present_fences_.emplace_back(nullptr);
        }
        self->fence_pending_.push_back(false);
    }
    if (device.enabled().display_timing) {
        if (auto refresh = self->swapchain_.getRefreshCycleDurationGOOGLE())
            self->refresh_ns_ = refresh->refreshDuration;
    }
    return self;
}

Acquired Swapchain::acquire(vk::Semaphore acquired, std::uint32_t& index) {
    const vk::ResultValue<std::uint32_t> r = swapchain_.acquireNextImage(UINT64_MAX, acquired, nullptr);
    switch (r.result) {
    case vk::Result::eSuccess: index = r.value; return Acquired::ok;
    case vk::Result::eSuboptimalKHR:
        index = r.value;
        stale_ = true;
        return Acquired::suboptimal;
    case vk::Result::eErrorOutOfDateKHR: stale_ = true; return Acquired::out_of_date;
    case vk::Result::eTimeout:
    case vk::Result::eNotReady: return Acquired::timeout;
    case vk::Result::eErrorDeviceLost: return Acquired::lost;
    default:
        log::error("vk acquire failed: {}", vk::to_string(r.result));
        stale_ = true;
        return Acquired::out_of_date;
    }
}

Acquired Swapchain::present(std::uint32_t index, std::uint64_t frame, std::uint64_t desired_ns) {
    const vk::Semaphore wait = *rendered_[index];
    const vk::SwapchainKHR chain = *swapchain_;
    void* next = nullptr;
    // A fence per present (swapchain_maintenance1): the previous present of this image has long finished; wait for it
    // (it does not block) before the fence is used again.
    vk::Fence fence;
    if (*present_fences_[index]) {
        if (fence_pending_[index]) (void)device_->handle().waitForFences(*present_fences_[index], vk::True, UINT64_MAX);
        std::ignore = device_->handle().resetFences(
            *present_fences_[index]); // fails only on a lost device, which the present reports
        fence = *present_fences_[index];
        fence_pending_[index] = true;
    }
    vk::SwapchainPresentFenceInfoKHR fence_info{.swapchainCount = 1, .pFences = &fence};
    if (fence) next = &fence_info;
    const vk::PresentTimeGOOGLE time{.presentID = static_cast<std::uint32_t>(frame), .desiredPresentTime = desired_ns};
    vk::PresentTimesInfoGOOGLE times{.pNext = next, .swapchainCount = 1, .pTimes = &time};
    if (device_->enabled().display_timing) next = &times;
    const vk::PresentInfoKHR info{.pNext = next,
                                  .waitSemaphoreCount = 1,
                                  .pWaitSemaphores = &wait,
                                  .swapchainCount = 1,
                                  .pSwapchains = &chain,
                                  .pImageIndices = &index};
    const vk::Result r = device_->queue().presentKHR(info);
    switch (r) {
    case vk::Result::eSuccess: return Acquired::ok;
    case vk::Result::eSuboptimalKHR: stale_ = true; return Acquired::suboptimal;
    case vk::Result::eErrorOutOfDateKHR: stale_ = true; return Acquired::out_of_date;
    case vk::Result::eErrorDeviceLost: return Acquired::lost;
    default:
        log::error("vk present failed: {}", vk::to_string(r));
        stale_ = true;
        return Acquired::out_of_date;
    }
}

void Swapchain::past_timings(std::vector<PresentedTiming>& out) {
    if (!device_->enabled().display_timing) return;
    auto list = swapchain_.getPastPresentationTimingGOOGLE();
    if (!list) return;
    for (const vk::PastPresentationTimingGOOGLE& t : *list) {
        out.push_back({.present_id = t.presentID,
                       .desired_ns = t.desiredPresentTime,
                       .actual_ns = t.actualPresentTime,
                       .earliest_ns = t.earliestPresentTime,
                       .margin_ns = t.presentMargin});
    }
}

} // namespace atlas::gpu
