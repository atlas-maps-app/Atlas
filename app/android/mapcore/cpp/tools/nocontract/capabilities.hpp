// Capabilities a module declares but does not need. Slang 2026.19 declares StorageImageReadWithoutFormat for every
// read of an image of unknown format, subpass inputs included, though SPIR-V exempts subpass data from that rule and
// glslang does not declare it. Vulkan then requires shaderStorageImageReadWithoutFormat (or Vulkan 1.3) of the device,
// which many Vulkan 1.1 phones lack, so the composite's module would be invalid there. A module needs the two
// without-format capabilities only if it has a storage image (Sampled 2) of unknown format whose Dim is not
// SubpassData; without one, they are removed.
#pragma once

#include "spirv.hpp"

#include <cstdint>
#include <span>
#include <utility>
#include <vector>

namespace atlas::spirv {

inline constexpr std::uint16_t op_type_image = 25;
inline constexpr std::uint32_t capability_storage_image_read_without_format = 55;
inline constexpr std::uint32_t capability_storage_image_write_without_format = 56;
inline constexpr std::uint32_t dim_subpass_data = 6;
inline constexpr std::uint32_t image_format_unknown = 0;

/// Whether the module has a storage image of unknown format that is not subpass data.
[[nodiscard]] inline bool has_formatless_storage_image(std::span<const Instruction> code) {
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        // OpTypeImage: result, sampled type, Dim, Depth, Arrayed, MS, Sampled, Image Format[, access qualifier].
        if (in.opcode == op_type_image && ops.size() >= 8) {
            const bool storage = ops[6] == 2;
            if (storage && ops[7] == image_format_unknown && ops[2] != dim_subpass_data) return true;
        }
    }
    return false;
}

/// The module without the without-format capabilities when it has no image that needs them, and how many it removed.
[[nodiscard]] inline std::pair<std::vector<std::uint32_t>, std::size_t>
remove_unneeded_capabilities(std::span<const std::uint32_t> module, std::span<const Instruction> code) {
    const bool needed = has_formatless_storage_image(code);
    std::vector<std::uint32_t> out(module.begin(), module.begin() + static_cast<std::ptrdiff_t>(header_words));
    std::size_t removed = 0;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        const bool without_format = in.opcode == op_capability && ops.size() == 1 &&
                                    (ops[0] == capability_storage_image_read_without_format ||
                                     ops[0] == capability_storage_image_write_without_format);
        if (without_format && !needed) {
            ++removed;
            continue;
        }
        out.insert(out.end(), in.words.begin(), in.words.end());
    }
    return {std::move(out), removed};
}

} // namespace atlas::spirv
