// The device layer's test session: the host's Vulkan (lavapipe on this Mac and on CI) under the validation layer
// with synchronization validation, opened through the core's own Instance and Device. Each test opens its own
// session and closes it before it ends, so no Vulkan object outlives the validation layer at exit.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Instance.hpp"

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

/// Opens a session, or reports why not: a failure when Vulkan is required, else a skip (an empty optional).
[[nodiscard]] inline std::optional<Session> open_session(const gpu::DeviceOptions& options = {}) {
    auto instance = gpu::Instance::open({.validation = true, .require_validation = vulkan_required()});
    if (!instance) {
        if (vulkan_required()) FAIL("Vulkan is required (ATLAS_REQUIRE_VULKAN=1) but: " << instance.error().text());
        MESSAGE("skipped: " << instance.error().text());
        return std::nullopt;
    }
    Session session;
    session.instance = std::move(*instance);
    auto device = gpu::Device::open(*session.instance, options);
    if (!device) {
        FAIL("no device: " << device.error().text());
        return std::nullopt;
    }
    session.device = std::move(*device);
    return session;
}

} // namespace atlas::test
