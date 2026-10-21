// The quirk table (plan-vector D5.10): what VK bench and VK check find on real phones, keyed by driver ID, device ID
// and a range of driver versions. An entry fixes the frame configuration, the frame route, the frame form (ONEPASS) or
// the composite's target (the direct form where VK facts' composite check finds the driver clipping the full-screen
// triangle, frame/CompositeCheck.hpp), sends the pipelines through Slang's GLSL route, or turns the GPU noise path off,
// and names the check that proves it.
// Unity ships a deny list for the same reason (N12). Empty until A0's paste brings the first entry.
#pragma once

#include "gpu/Facts.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace atlas::gpu {

/// What the table changes for a device; unset fields leave the facts' own choice.
struct QuirkSet {
    std::optional<FrameConfig> config;
    std::optional<Route> route;
    std::optional<FrameForm> form;
    /// The composite's target: direct takes the render pass's route with it (one dynamic rendering scope keeps one
    /// sample count).
    std::optional<CompositeTarget> target;
    bool glsl_route = false;
    bool gpu_noise_off = false;
    /// The entries that matched, each with its reason ("none" when nothing did).
    std::string text = "none";

    [[nodiscard]] bool empty() const noexcept {
        return !config && !route && !form && !target && !glsl_route && !gpu_noise_off;
    }
};

/// One row of the table: which drivers it matches, and what it changes.
struct Quirk {
    std::uint32_t device_id = 0;            // 0: every device of the driver
    std::uint32_t version_min = 0;          // inclusive
    std::uint32_t version_max = UINT32_MAX; // inclusive
    std::optional<FrameConfig> config;
    std::optional<Route> route;
    std::optional<FrameForm> form;
    std::optional<CompositeTarget> target;
    bool glsl_route = false;
    bool gpu_noise_off = false;
    std::string_view why; // the measurement that proves it ("A0: <phone> composite +1.4 ms over one subpass")
};

/// The rows that match the device (by driver ID, else by vendor for drivers without driver properties), merged.
[[nodiscard]] QuirkSet quirks_for(std::uint32_t vendor_id, bool has_driver_id, vk::DriverId driver_id,
                                  std::uint32_t device_id, std::uint32_t driver_version);

/// How many rows the table has (VK facts prints it).
[[nodiscard]] std::size_t quirk_rows() noexcept;

} // namespace atlas::gpu
