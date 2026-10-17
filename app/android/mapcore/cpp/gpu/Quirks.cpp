// The quirk table (gpu/Quirks.hpp). Rows are added from measurements on real phones (A0's VK bench, VK check), each
// with the check that proves it; nothing here is guessed.
#include "gpu/Quirks.hpp"

#include <flat_map>
#include <format>
#include <utility>

namespace atlas::gpu {

namespace {

/// The table's key: the driver (its VkDriverId, or 0x80000000 | vendor ID for a driver without driver properties).
using Key = std::uint32_t;

[[nodiscard]] Key key_of(std::uint32_t vendor_id, bool has_driver_id, vk::DriverId driver_id) noexcept {
    return has_driver_id ? static_cast<Key>(driver_id) : (0x8000'0000U | vendor_id);
}

/// The rows, by driver. A0 brings the first: the owner's phone's frame configuration, route and ONEPASS decision
/// where its numbers differ from what the facts alone choose.
[[nodiscard]] const std::flat_multimap<Key, Quirk>& table() {
    static const std::flat_multimap<Key, Quirk> rows{};
    return rows;
}

} // namespace

QuirkSet quirks_for(std::uint32_t vendor_id, bool has_driver_id, vk::DriverId driver_id, std::uint32_t device_id,
                    std::uint32_t driver_version) {
    QuirkSet set;
    std::string text;
    const auto [first, last] = table().equal_range(key_of(vendor_id, has_driver_id, driver_id));
    for (auto it = first; it != last; ++it) {
        const Quirk& q = it->second;
        if (q.device_id != 0 && q.device_id != device_id) continue;
        if (driver_version < q.version_min || driver_version > q.version_max) continue;
        if (q.config) set.config = q.config;
        if (q.route) set.route = q.route;
        if (q.form) set.form = q.form;
        set.glsl_route |= q.glsl_route;
        set.gpu_noise_off |= q.gpu_noise_off;
        text += std::format("{}{}", text.empty() ? "" : "; ", q.why);
    }
    if (!text.empty()) set.text = std::move(text);
    return set;
}

std::size_t quirk_rows() noexcept { return table().size(); }

} // namespace atlas::gpu
