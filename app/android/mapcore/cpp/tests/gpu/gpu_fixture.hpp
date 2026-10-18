// The device layer's test session: the host's Vulkan (lavapipe on this Mac and on CI) under the validation layer
// with synchronization validation, opened through the core's own Instance and Device, for offscreen work unless a test
// asks for presentation. Each test opens its own session and closes it before it ends, so no Vulkan object outlives
// the validation layer at exit. What a test cannot go on without, it requires with ATLAS_REQUIRE
// (tests/base/require.hpp): doctest without exceptions carries on past a failed REQUIRE.
//
// Where presentation is expected: wherever the host's Vulkan has a window system, its instance offering
// VK_KHR_surface, as the Android emulator package's lavapipe does on this Mac and on the x86_64 runner. There a test
// that presents and cannot fails when Vulkan is required (ATLAS_REQUIRE_VULKAN=1). The arm64 runner's lavapipe is Mesa
// built without a window system (.github/docker/lavapipe: -Dplatforms=), with no VK_KHR_surface and no
// VK_KHR_swapchain: it only works offscreen, and the tests that present skip there, saying why, or (the Vulkan 1.1
// case) work offscreen instead.
// ATLAS_VULKAN_HIDE=VK_KHR_surface,VK_KHR_swapchain makes this Mac's lavapipe look like it.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Instance.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <cstdlib>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace atlas::test {

/// Whether the environment demands Vulkan (CI and the presets set ATLAS_REQUIRE_VULKAN=1).
[[nodiscard]] inline bool vulkan_required() {
    const char* value =
        std::getenv("ATLAS_REQUIRE_VULKAN"); // NOLINT(concurrency-mt-unsafe): tests read it before threads
    return value != nullptr && std::string(value) == "1";
}

/// An instance and a device, closed in order.
struct Session {
    std::unique_ptr<gpu::Instance> instance;
    std::unique_ptr<gpu::Device> device;

    Session() = default;
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = default;
    Session& operator=(Session&&) = default;
    ~Session() {
        if (device) device->wait_idle();
        device.reset();
        instance.reset();
    }

    /// The validation layer's messages since the last call.
    [[nodiscard]] std::vector<std::string> take_messages() const { return instance->messages().take(); }
};

/// Opens an instance, or reports why not: a failure when Vulkan is required, else a skip (nullptr).
[[nodiscard]] inline std::unique_ptr<gpu::Instance> open_instance(const gpu::InstanceOptions& options) {
    auto instance = gpu::Instance::open(options);
    if (instance) return std::move(*instance);
    if (vulkan_required()) {
        FAIL("Vulkan is required (ATLAS_REQUIRE_VULKAN=1) but: " << instance.error().text());
    } else {
        MESSAGE("skipped: " << instance.error().text());
    }
    return nullptr;
}

/// Opens a session, or reports why not: a failure when Vulkan is required or the instance has no device for it, else a
/// skip (an empty optional). The device works offscreen unless `options` asks it to present. The tests draw at both
/// depths, so the device is asked about its 10-bit formats at once (Device::check_ten_bit), as VK bench's colour stage
/// asks the phone's as it begins.
[[nodiscard]] inline std::optional<Session> open_session(const gpu::DeviceOptions& options = {}) {
    Session session;
    session.instance = open_instance({.validation = true, .require_validation = vulkan_required()});
    if (!session.instance) return std::nullopt;
    // Both frame routes wherever the device can draw them: lavapipe's dynamic route is one its Vulkan 1.4 properties
    // forbid (no local reads of multisampled or depth-stencil attachments), forced for coverage, as CI's only GPU.
    gpu::DeviceOptions forced = options;
    forced.force_local_read = true;
    auto device = gpu::Device::open(*session.instance, forced);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, device.has_value(), device.error().text());
    session.device = std::move(*device);
    session.device->check_ten_bit();
    return session;
}

/// Whether presentation is expected of the host's Vulkan: it has a window system (above).
[[nodiscard]] inline bool presentation_expected(const gpu::Instance& instance) {
    return instance.has_extension(vk::KHRSurfaceExtensionName);
}

/// Whether a test that presents must: presentation is expected of the host's Vulkan, and Vulkan is required.
[[nodiscard]] inline bool presentation_demanded(const gpu::Instance& instance) {
    return vulkan_required() && presentation_expected(instance);
}

/// Why a device with `facts` cannot present on `instance`'s Vulkan ("" when it can): no window system (no
/// VK_KHR_surface on the instance), or no VK_KHR_swapchain on the device.
[[nodiscard]] inline std::string why_no_presenting(const gpu::Instance& instance, const gpu::Facts& facts) {
    if (!instance.has_extension(vk::KHRSurfaceExtensionName)) {
        return "this Vulkan has no window system (no VK_KHR_surface on the instance)";
    }
    if (!facts.has_extension(vk::KHRSwapchainExtensionName)) return facts.name + " has no VK_KHR_swapchain";
    return {};
}

/// Reports that a test which presents cannot, for `why`: a failure where presentation is demanded (above), else a
/// skip with the reason. The test returns after it.
inline void cannot_present(const gpu::Instance& instance, const std::string& why) {
    if (presentation_demanded(instance)) {
        FAIL("this Vulkan has a window system (VK_KHR_surface) and Vulkan is required (ATLAS_REQUIRE_VULKAN=1), so it "
             "must present, but "
             << why);
    } else {
        MESSAGE("skipped: " << why);
    }
}

} // namespace atlas::test
