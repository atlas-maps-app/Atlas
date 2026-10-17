// Descriptors (gpu/Descriptors.hpp).
#include "gpu/Descriptors.hpp"

#include <map>

namespace atlas::gpu {

Result<vk::raii::DescriptorSetLayout> make_set_layout(const vk::raii::Device& device,
                                                      std::span<const Binding> bindings) {
    std::vector<vk::DescriptorSetLayoutBinding> list;
    list.reserve(bindings.size());
    for (const Binding& b : bindings) {
        list.push_back(
            {.binding = b.slot, .descriptorType = b.type, .descriptorCount = b.count, .stageFlags = b.stages});
    }
    ATLAS_VK_LET(layout,
                 device.createDescriptorSetLayout(
                     {.bindingCount = static_cast<std::uint32_t>(list.size()), .pBindings = list.data()}),
                 "a descriptor set layout");
    return std::move(layout);
}

Result<vk::raii::DescriptorPool> make_pool(const vk::raii::Device& device,
                                           std::span<const std::span<const Binding>> layouts, std::uint32_t sets) {
    std::map<vk::DescriptorType, std::uint32_t> counts;
    for (const std::span<const Binding> bindings : layouts) {
        for (const Binding& b : bindings) counts[b.type] += b.count * sets;
    }
    std::vector<vk::DescriptorPoolSize> sizes;
    sizes.reserve(counts.size());
    for (const auto& [type, n] : counts) sizes.push_back({.type = type, .descriptorCount = n});
    ATLAS_VK_LET(pool,
                 device.createDescriptorPool({.flags = vk::DescriptorPoolCreateFlagBits::eFreeDescriptorSet,
                                              .maxSets = sets * static_cast<std::uint32_t>(layouts.size()),
                                              .poolSizeCount = static_cast<std::uint32_t>(sizes.size()),
                                              .pPoolSizes = sizes.data()}),
                 "a descriptor pool");
    return std::move(pool);
}

Result<vk::raii::DescriptorSet> allocate_set(const vk::raii::Device& device, const vk::raii::DescriptorPool& pool,
                                             const vk::raii::DescriptorSetLayout& layout) {
    const vk::DescriptorSetLayout raw = *layout;
    ATLAS_VK_LET(sets,
                 device.allocateDescriptorSets({.descriptorPool = *pool, .descriptorSetCount = 1, .pSetLayouts = &raw}),
                 "a descriptor set");
    return std::move(sets.front());
}

SetWriter& SetWriter::buffer(std::uint32_t slot, vk::DescriptorType type, vk::Buffer buffer, vk::DeviceSize offset,
                             vk::DeviceSize range) {
    buffers_.push_back({.buffer = buffer, .offset = offset, .range = range});
    pending_.push_back({slot, type, false, buffers_.size() - 1});
    return *this;
}

SetWriter& SetWriter::image(std::uint32_t slot, vk::DescriptorType type, vk::ImageView view, vk::ImageLayout layout,
                            vk::Sampler sampler) {
    images_.push_back({.sampler = sampler, .imageView = view, .imageLayout = layout});
    pending_.push_back({slot, type, true, images_.size() - 1});
    return *this;
}

void SetWriter::apply(const vk::raii::Device& device) {
    std::vector<vk::WriteDescriptorSet> writes;
    writes.reserve(pending_.size());
    for (const Pending& p : pending_) {
        writes.push_back({.dstSet = set_,
                          .dstBinding = p.slot,
                          .descriptorCount = 1,
                          .descriptorType = p.type,
                          .pImageInfo = p.is_image ? &images_[p.index] : nullptr,
                          .pBufferInfo = p.is_image ? nullptr : &buffers_[p.index]});
    }
    device.updateDescriptorSets(writes, {});
    pending_.clear();
    buffers_.clear();
    images_.clear();
}

} // namespace atlas::gpu
