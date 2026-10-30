// Barriers behind one wrapper (plan-vector D5.2): synchronization2 where the device has it, the same dependencies as
// Vulkan 1.0 calls elsewhere. Callers describe barriers with the synchronization2 types, using only the stages and
// accesses that exist in both forms (the low 32 bits), so either route records the same dependency.
#pragma once

#include "base/vulkan.hpp"

#include <cstdint>
#include <span>

namespace atlas::gpu {

/// A global memory dependency.
struct MemoryDependency {
    vk::PipelineStageFlags2 src_stage;
    vk::AccessFlags2 src_access;
    vk::PipelineStageFlags2 dst_stage;
    vk::AccessFlags2 dst_access;
};

/// An image's dependency and layout change.
struct ImageDependency {
    vk::Image image;
    vk::ImageAspectFlags aspect = vk::ImageAspectFlagBits::eColor;
    vk::PipelineStageFlags2 src_stage;
    vk::AccessFlags2 src_access;
    vk::PipelineStageFlags2 dst_stage;
    vk::AccessFlags2 dst_access;
    vk::ImageLayout old_layout = vk::ImageLayout::eUndefined;
    vk::ImageLayout new_layout = vk::ImageLayout::eUndefined;
    /// The mipmap levels it covers: from `base_level`, `levels` of them (every level by default).
    std::uint32_t base_level = 0;
    std::uint32_t levels = VK_REMAINING_MIP_LEVELS;
};

/// Records barriers in whichever form the device has.
class Sync {
public:
    explicit Sync(bool synchronization2) noexcept : sync2_(synchronization2) {}

    [[nodiscard]] bool synchronization2() const noexcept { return sync2_; }

    void barrier(const vk::raii::CommandBuffer& cmd, std::span<const MemoryDependency> memory,
                 std::span<const ImageDependency> images, vk::DependencyFlags flags = {}) const;

    void barrier(const vk::raii::CommandBuffer& cmd, const ImageDependency& image,
                 vk::DependencyFlags flags = {}) const {
        barrier(cmd, {}, std::span(&image, 1), flags);
    }

    void barrier(const vk::raii::CommandBuffer& cmd, const MemoryDependency& memory,
                 vk::DependencyFlags flags = {}) const {
        barrier(cmd, std::span(&memory, 1), {}, flags);
    }

private:
    bool sync2_;
};

} // namespace atlas::gpu
