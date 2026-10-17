// Barriers in either form (gpu/Sync.hpp).
#include "gpu/Sync.hpp"

#include "base/assert.hpp"

#include <array>
#include <vector>

namespace atlas::gpu {

namespace {

/// The legacy form of a synchronization2 stage mask: the same bits, which callers keep within the first 32.
[[nodiscard]] vk::PipelineStageFlags legacy(vk::PipelineStageFlags2 stages) noexcept {
    const auto bits = static_cast<std::uint64_t>(static_cast<VkPipelineStageFlags2>(stages));
    ATLAS_DEBUG_ASSERT((bits >> 32U) == 0U, "a stage only synchronization2 has");
    if (bits == 0U) return vk::PipelineStageFlagBits::eTopOfPipe;
    return vk::PipelineStageFlags(static_cast<VkPipelineStageFlags>(bits));
}

[[nodiscard]] vk::AccessFlags legacy(vk::AccessFlags2 access) noexcept {
    const auto bits = static_cast<std::uint64_t>(static_cast<VkAccessFlags2>(access));
    ATLAS_DEBUG_ASSERT((bits >> 32U) == 0U, "an access only synchronization2 has");
    return vk::AccessFlags(static_cast<VkAccessFlags>(bits));
}

[[nodiscard]] vk::ImageSubresourceRange whole(vk::ImageAspectFlags aspect) noexcept {
    return {.aspectMask = aspect,
            .baseMipLevel = 0,
            .levelCount = VK_REMAINING_MIP_LEVELS,
            .baseArrayLayer = 0,
            .layerCount = VK_REMAINING_ARRAY_LAYERS};
}

} // namespace

void Sync::barrier(const vk::raii::CommandBuffer& cmd, std::span<const MemoryDependency> memory,
                   std::span<const ImageDependency> images, vk::DependencyFlags flags) const {
    constexpr std::size_t inline_count = 8;
    if (sync2_) {
        std::array<vk::MemoryBarrier2, inline_count> m{};
        std::array<vk::ImageMemoryBarrier2, inline_count> i{};
        ATLAS_ASSERT(memory.size() <= inline_count && images.size() <= inline_count, "at most eight barriers at once");
        for (std::size_t k = 0; k < memory.size(); ++k) {
            m[k] = {.srcStageMask = memory[k].src_stage,
                    .srcAccessMask = memory[k].src_access,
                    .dstStageMask = memory[k].dst_stage,
                    .dstAccessMask = memory[k].dst_access};
        }
        for (std::size_t k = 0; k < images.size(); ++k) {
            const ImageDependency& d = images[k];
            i[k] = {.srcStageMask = d.src_stage,
                    .srcAccessMask = d.src_access,
                    .dstStageMask = d.dst_stage,
                    .dstAccessMask = d.dst_access,
                    .oldLayout = d.old_layout,
                    .newLayout = d.new_layout,
                    .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                    .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                    .image = d.image,
                    .subresourceRange = whole(d.aspect)};
        }
        cmd.pipelineBarrier2({.dependencyFlags = flags,
                              .memoryBarrierCount = static_cast<std::uint32_t>(memory.size()),
                              .pMemoryBarriers = m.data(),
                              .imageMemoryBarrierCount = static_cast<std::uint32_t>(images.size()),
                              .pImageMemoryBarriers = i.data()});
        return;
    }
    vk::PipelineStageFlags src{};
    vk::PipelineStageFlags dst{};
    std::array<vk::MemoryBarrier, inline_count> m{};
    std::array<vk::ImageMemoryBarrier, inline_count> i{};
    ATLAS_ASSERT(memory.size() <= inline_count && images.size() <= inline_count, "at most eight barriers at once");
    for (std::size_t k = 0; k < memory.size(); ++k) {
        src |= legacy(memory[k].src_stage);
        dst |= legacy(memory[k].dst_stage);
        m[k] = {.srcAccessMask = legacy(memory[k].src_access), .dstAccessMask = legacy(memory[k].dst_access)};
    }
    for (std::size_t k = 0; k < images.size(); ++k) {
        const ImageDependency& d = images[k];
        src |= legacy(d.src_stage);
        dst |= legacy(d.dst_stage);
        i[k] = {.srcAccessMask = legacy(d.src_access),
                .dstAccessMask = legacy(d.dst_access),
                .oldLayout = d.old_layout,
                .newLayout = d.new_layout,
                .srcQueueFamilyIndex = vk::QueueFamilyIgnored,
                .dstQueueFamilyIndex = vk::QueueFamilyIgnored,
                .image = d.image,
                .subresourceRange = whole(d.aspect)};
    }
    if (!src) src = vk::PipelineStageFlagBits::eTopOfPipe;
    if (!dst) dst = vk::PipelineStageFlagBits::eBottomOfPipe;
    cmd.pipelineBarrier(src, dst, flags, std::span(m.data(), memory.size()), {}, std::span(i.data(), images.size()));
}

} // namespace atlas::gpu
