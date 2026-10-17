// GPU memory (gpu/Memory.hpp). The core's one copy of the Vulkan Memory Allocator is compiled here, with the
// allocator's Vulkan functions taken from the loader at run time (VMA_DYNAMIC_VULKAN_FUNCTIONS, set in Memory.hpp).
#include "gpu/Memory.hpp"

#include "gpu/Instance.hpp"

#define VMA_IMPLEMENTATION
#include <vk_mem_alloc.h>

#include <format>

namespace atlas::gpu {

namespace {

[[nodiscard]] VmaAllocationCreateInfo allocation_for(MemoryUse use) noexcept {
    VmaAllocationCreateInfo info{};
    switch (use) {
    case MemoryUse::gpu: info.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE; break;
    case MemoryUse::lazy: info.usage = VMA_MEMORY_USAGE_GPU_LAZILY_ALLOCATED; break;
    case MemoryUse::upload:
        info.usage = VMA_MEMORY_USAGE_AUTO;
        info.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT;
        break;
    case MemoryUse::readback:
        info.usage = VMA_MEMORY_USAGE_AUTO;
        info.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT;
        break;
    }
    return info;
}

} // namespace

Image::~Image() {
    if (allocator_ != nullptr && image_) vmaDestroyImage(allocator_, static_cast<VkImage>(image_), allocation_);
}

Buffer::~Buffer() {
    if (allocator_ != nullptr && buffer_) vmaDestroyBuffer(allocator_, static_cast<VkBuffer>(buffer_), allocation_);
}

void Buffer::flush() const {
    if (allocation_ != nullptr) (void)vmaFlushAllocation(allocator_, allocation_, 0, VK_WHOLE_SIZE);
}

void Buffer::invalidate() const {
    if (allocation_ != nullptr) (void)vmaInvalidateAllocation(allocator_, allocation_, 0, VK_WHOLE_SIZE);
}

Memory::~Memory() {
    if (allocator_ != nullptr) vmaDestroyAllocator(allocator_);
}

Result<std::unique_ptr<Memory>> Memory::create(const Instance& instance, const vk::raii::PhysicalDevice& physical,
                                               const vk::raii::Device& device, std::uint32_t version, bool budget) {
    std::unique_ptr<Memory> self(new Memory()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    VmaVulkanFunctions functions{};
    functions.vkGetInstanceProcAddr = instance.get_instance_proc_addr();
    functions.vkGetDeviceProcAddr = device.getDispatcher()->vkGetDeviceProcAddr;
    VmaAllocatorCreateInfo info{};
    info.flags = budget ? VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT : 0U;
    info.physicalDevice = static_cast<VkPhysicalDevice>(*physical);
    info.device = static_cast<VkDevice>(*device);
    info.pVulkanFunctions = &functions;
    info.instance = static_cast<VkInstance>(*instance.handle());
    // VMA uses what the device offers up to its own newest version; the core never asks above 1.4.
    info.vulkanApiVersion = VK_MAKE_API_VERSION(0, VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version), 0);
    if (const VkResult r = vmaCreateAllocator(&info, &self->allocator_); r != VK_SUCCESS) {
        return fail(static_cast<vk::Result>(r), "vmaCreateAllocator");
    }
    return self;
}

Result<Image> Memory::image(const vk::ImageCreateInfo& info, MemoryUse use) const {
    const VmaAllocationCreateInfo allocation = allocation_for(use);
    Image image;
    VkImage raw = VK_NULL_HANDLE;
    VmaAllocationInfo details{};
    const auto& c_info = static_cast<const VkImageCreateInfo&>(info);
    if (const VkResult r = vmaCreateImage(allocator_, &c_info, &allocation, &raw, &image.allocation_, &details);
        r != VK_SUCCESS) {
        return fail(static_cast<vk::Result>(r),
                    std::format("an image of {} x {} ({}, {} samples, {})", info.extent.width, info.extent.height,
                                vk::to_string(info.format), static_cast<int>(info.samples),
                                use == MemoryUse::lazy ? "lazily allocated" : "ordinary"));
    }
    image.allocator_ = allocator_;
    image.image_ = raw;
    image.format_ = info.format;
    image.extent_ = vk::Extent2D{.width = info.extent.width, .height = info.extent.height};
    image.samples_ = info.samples;
    image.lazy_ = use == MemoryUse::lazy;
    image.bytes_ = image.lazy_ ? 0 : details.size;
    return image;
}

Result<Buffer> Memory::buffer(vk::DeviceSize size, vk::BufferUsageFlags usage, MemoryUse use) const {
    const VmaAllocationCreateInfo allocation = allocation_for(use);
    const vk::BufferCreateInfo info{.size = size, .usage = usage, .sharingMode = vk::SharingMode::eExclusive};
    const auto& c_info = static_cast<const VkBufferCreateInfo&>(info);
    Buffer buffer;
    VkBuffer raw = VK_NULL_HANDLE;
    VmaAllocationInfo details{};
    if (const VkResult r = vmaCreateBuffer(allocator_, &c_info, &allocation, &raw, &buffer.allocation_, &details);
        r != VK_SUCCESS) {
        return fail(static_cast<vk::Result>(r), std::format("a buffer of {} bytes", size));
    }
    buffer.allocator_ = allocator_;
    buffer.buffer_ = raw;
    buffer.size_ = size;
    buffer.mapped_ = details.pMappedData;
    return buffer;
}

MemoryStats Memory::stats() const {
    MemoryStats out;
    VmaTotalStatistics total{};
    vmaCalculateStatistics(allocator_, &total);
    out.allocated = total.total.statistics.allocationBytes;
    out.blocks = total.total.statistics.blockBytes;
    const VkPhysicalDeviceMemoryProperties* properties = nullptr;
    vmaGetMemoryProperties(allocator_, &properties);
    std::array<VmaBudget, VK_MAX_MEMORY_HEAPS> budgets{};
    vmaGetHeapBudgets(allocator_, budgets.data());
    for (std::uint32_t i = 0; i < properties->memoryHeapCount; ++i) {
        out.budget += budgets[i].budget;
        out.usage += budgets[i].usage;
    }
    for (std::uint32_t t = 0; t < properties->memoryTypeCount; ++t) {
        if ((properties->memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) != 0U) {
            out.lazy += total.memoryType[t].statistics.allocationBytes;
        }
    }
    return out;
}

} // namespace atlas::gpu
