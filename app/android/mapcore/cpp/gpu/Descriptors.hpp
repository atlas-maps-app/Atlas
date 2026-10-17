// Descriptors (plan-vector D5.6): set layouts from a list of bindings, a pool sized for them, and the writes that
// point a set at buffers, images and input attachments. Sets are written when resources change (a resize, a heap's
// growth), never per frame; per-draw values travel as push constants.
#pragma once

#include "gpu/Error.hpp"

#include <cstdint>
#include <initializer_list>
#include <span>
#include <vector>

namespace atlas::gpu {

/// One binding of a set: its slot, its type, how many, and the stages that read it.
struct Binding {
    std::uint32_t slot;
    vk::DescriptorType type;
    vk::ShaderStageFlags stages;
    std::uint32_t count = 1;
};

/// A set layout from its bindings.
[[nodiscard]] Result<vk::raii::DescriptorSetLayout> make_set_layout(const vk::raii::Device& device,
                                                                    std::span<const Binding> bindings);

/// A pool with room for `sets` sets of each of `layouts`' bindings (one list of bindings per kind of set).
[[nodiscard]] Result<vk::raii::DescriptorPool>
make_pool(const vk::raii::Device& device, std::span<const std::span<const Binding>> layouts, std::uint32_t sets);

/// Allocates one set of `layout` from `pool`.
[[nodiscard]] Result<vk::raii::DescriptorSet> allocate_set(const vk::raii::Device& device,
                                                           const vk::raii::DescriptorPool& pool,
                                                           const vk::raii::DescriptorSetLayout& layout);

/// The writes of one set, gathered and applied at once.
class SetWriter {
public:
    explicit SetWriter(vk::DescriptorSet set) noexcept : set_(set) {}
    SetWriter& buffer(std::uint32_t slot, vk::DescriptorType type, vk::Buffer buffer, vk::DeviceSize offset = 0,
                      vk::DeviceSize range = vk::WholeSize);
    SetWriter& image(std::uint32_t slot, vk::DescriptorType type, vk::ImageView view, vk::ImageLayout layout,
                     vk::Sampler sampler = {});
    void apply(const vk::raii::Device& device);

private:
    vk::DescriptorSet set_;
    std::vector<vk::DescriptorBufferInfo> buffers_;
    std::vector<vk::DescriptorImageInfo> images_;
    struct Pending {
        std::uint32_t slot;
        vk::DescriptorType type;
        bool is_image;
        std::size_t index;
    };
    std::vector<Pending> pending_;
};

} // namespace atlas::gpu
