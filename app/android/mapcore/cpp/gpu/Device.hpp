// The device (plan-vector D5.11): the physical device chosen from the loader's list, its facts and the frame they
// decide, the logical device with the extensions and features the frame may use, one queue that draws, computes and
// (when the device presents) presents, and the allocator. Presenting is the caller's choice: the phone's surface path
// and the Android façade's device present; the core's offscreen work (the host tests, the likeness candidate, the
// light probe, VK check, the bench's offscreen passes) opens a device without VK_KHR_swapchain and what only
// presenting uses.
#pragma once

#include "gpu/Facts.hpp"
#include "gpu/Instance.hpp"
#include "gpu/Memory.hpp"

#include <cstdint>
#include <memory>
#include <optional>

namespace atlas::gpu {

/// How to choose and open the device.
struct DeviceOptions {
    /// A surface the queue must present to (none: a device that presents to surfaces made later, or offscreen).
    vk::SurfaceKHR surface;
    /// The device presents: it needs VK_KHR_swapchain, and gets what only presenting uses (swapchain maintenance,
    /// display timing, incremental present). A surface implies it. Off, the device works offscreen and opens on a
    /// Vulkan without a window system (the arm64 runner's lavapipe); its facts still say whether it has the swapchain.
    bool present = false;
    /// K6's answer to configuration B's condition (2x within 0.15 MAD of 4x in both classes).
    bool msaa2_close = false;
};

/// What the device was created with, which the frame's code may rely on.
struct Enabled {
    bool swapchain = false; // only on a device that presents (DeviceOptions::present)
    bool dynamic_rendering = false;
    bool local_read = false;
    bool synchronization2 = false;
    bool create_renderpass2 = false;
    bool swapchain_maintenance1 = false;
    bool display_timing = false;
    bool incremental_present = false;
    bool calibrated_timestamps = false;
    bool memory_budget = false;
    bool pipeline_cache_control = false;
    bool shader_fma = false;
    bool pipeline_statistics = false;
    bool independent_blend = false;
};

/// The physical device the core would use, with its facts, before anything is created on it (the crash guard checks
/// its driver first).
struct Picked {
    vk::raii::PhysicalDevice physical{nullptr};
    Facts facts;
};

/// The opened device.
class Device {
public:
    /// Chooses the physical device: the requirements met first, then a hardware GPU, then the newer API.
    [[nodiscard]] static Result<Picked> pick(Instance& instance, const DeviceOptions& options);
    /// Opens the device `pick` chose.
    [[nodiscard]] static Result<std::unique_ptr<Device>> open(Instance& instance, Picked picked,
                                                              const DeviceOptions& options);
    /// Both.
    [[nodiscard]] static Result<std::unique_ptr<Device>> open(Instance& instance, const DeviceOptions& options);

    Device(const Device&) = delete;
    Device& operator=(const Device&) = delete;
    Device(Device&&) = delete;
    Device& operator=(Device&&) = delete;
    ~Device();

    [[nodiscard]] Instance& instance() const noexcept { return *instance_; }
    [[nodiscard]] const vk::raii::PhysicalDevice& physical() const noexcept { return physical_; }
    [[nodiscard]] const vk::raii::Device& handle() const noexcept { return device_; }
    [[nodiscard]] const vk::raii::Queue& queue() const noexcept { return queue_; }
    [[nodiscard]] std::uint32_t queue_family() const noexcept { return queue_family_; }
    /// The version the device is used at: what both it and the instance offer.
    [[nodiscard]] std::uint32_t version() const noexcept { return version_; }
    [[nodiscard]] const Facts& facts() const noexcept { return facts_; }
    [[nodiscard]] Facts& facts() noexcept { return facts_; }
    [[nodiscard]] const Enabled& enabled() const noexcept { return enabled_; }
    [[nodiscard]] Memory& memory() const noexcept { return *memory_; }
    /// The queue family's valid timestamp bits (0: no timestamps).
    [[nodiscard]] std::uint32_t timestamp_bits() const noexcept { return timestamp_bits_; }
    /// Asks the device, as VK bench's colour stage begins, whether lazily allocated memory holds configuration A's 4x
    /// samples in the 10-bit formats (open asks it of the 8-bit ones only, so no 10-bit image reaches the driver
    /// before that stage and its crash guard), then decides the frame's colour again (decide_colour).
    void check_ten_bit();
    /// Whether the queue can present to `surface`.
    [[nodiscard]] bool can_present(vk::SurfaceKHR surface) const;
    /// Waits for the GPU to finish everything (shutdown and device loss only, never in the frame loop).
    void wait_idle() const;

private:
    Device() = default;

    Instance* instance_ = nullptr;
    vk::raii::PhysicalDevice physical_{nullptr};
    vk::raii::Device device_{nullptr};
    vk::raii::Queue queue_{nullptr};
    std::unique_ptr<Memory> memory_;
    Facts facts_;
    Enabled enabled_;
    std::uint32_t queue_family_ = 0;
    std::uint32_t version_ = 0;
    std::uint32_t timestamp_bits_ = 0;
};

} // namespace atlas::gpu
