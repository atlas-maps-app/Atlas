// GPU memory through the Vulkan Memory Allocator (plan-vector T5, D5.3, D5.7): images and buffers that free themselves,
// transient attachments in lazily allocated memory where the device has it (configuration A), host-visible buffers
// written in place on unified-memory phones, and the statistics VK bench reports as graphics memory.
#pragma once

#include "gpu/Error.hpp"

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#include <vk_mem_alloc.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <utility>

namespace atlas::gpu {

class Instance;

/// Where an image or a buffer lives.
enum class MemoryUse : std::uint8_t {
    gpu,      // device-local, never mapped
    lazy,     // transient attachments in lazily allocated memory: no pages on a tiler (configuration A)
    upload,   // host-visible, written sequentially by the CPU, read by the GPU (mapped for its lifetime)
    readback, // host-visible and cached, written by the GPU, read by the CPU (mapped for its lifetime)
};

/// An image and its memory, freed together.
class Image {
public:
    Image() = default;
    Image(const Image&) = delete;
    Image& operator=(const Image&) = delete;
    Image(Image&& other) noexcept { swap(other); }
    Image& operator=(Image&& other) noexcept {
        Image(std::move(other)).swap(*this);
        return *this;
    }
    ~Image();

    [[nodiscard]] vk::Image handle() const noexcept { return image_; }
    [[nodiscard]] vk::Format format() const noexcept { return format_; }
    [[nodiscard]] vk::Extent2D extent() const noexcept { return extent_; }
    [[nodiscard]] vk::SampleCountFlagBits samples() const noexcept { return samples_; }
    [[nodiscard]] bool lazy() const noexcept { return lazy_; }
    /// The bytes the allocation reserved (0 for lazily allocated memory, which has no pages until touched).
    [[nodiscard]] vk::DeviceSize bytes() const noexcept { return bytes_; }
    explicit operator bool() const noexcept { return image_; }

private:
    friend class Memory;
    void swap(Image& other) noexcept {
        std::swap(allocator_, other.allocator_);
        std::swap(allocation_, other.allocation_);
        std::swap(image_, other.image_);
        std::swap(format_, other.format_);
        std::swap(extent_, other.extent_);
        std::swap(samples_, other.samples_);
        std::swap(lazy_, other.lazy_);
        std::swap(bytes_, other.bytes_);
    }

    VmaAllocator allocator_ = nullptr;
    VmaAllocation allocation_ = nullptr;
    vk::Image image_;
    vk::Format format_ = vk::Format::eUndefined;
    vk::Extent2D extent_;
    vk::SampleCountFlagBits samples_ = vk::SampleCountFlagBits::e1;
    bool lazy_ = false;
    vk::DeviceSize bytes_ = 0;
};

/// A buffer and its memory; host-visible buffers stay mapped.
class Buffer {
public:
    Buffer() = default;
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
    Buffer(Buffer&& other) noexcept { swap(other); }
    Buffer& operator=(Buffer&& other) noexcept {
        Buffer(std::move(other)).swap(*this);
        return *this;
    }
    ~Buffer();

    [[nodiscard]] vk::Buffer handle() const noexcept { return buffer_; }
    [[nodiscard]] vk::DeviceSize size() const noexcept { return size_; }
    /// The mapped bytes of a host-visible buffer (empty otherwise).
    [[nodiscard]] std::span<std::byte> bytes() const noexcept {
        return mapped_ == nullptr ? std::span<std::byte>()
                                  : std::span(static_cast<std::byte*>(mapped_), static_cast<std::size_t>(size_));
    }
    /// Makes the CPU's writes visible to the GPU (a no-op on coherent memory).
    void flush() const;
    /// Makes the GPU's writes visible to the CPU (a no-op on coherent memory).
    void invalidate() const;
    explicit operator bool() const noexcept { return buffer_; }

private:
    friend class Memory;
    void swap(Buffer& other) noexcept {
        std::swap(allocator_, other.allocator_);
        std::swap(allocation_, other.allocation_);
        std::swap(buffer_, other.buffer_);
        std::swap(size_, other.size_);
        std::swap(mapped_, other.mapped_);
    }

    VmaAllocator allocator_ = nullptr;
    VmaAllocation allocation_ = nullptr;
    vk::Buffer buffer_;
    vk::DeviceSize size_ = 0;
    void* mapped_ = nullptr;
};

/// What the allocator holds, for VK bench's graphics line and the CI gate.
struct MemoryStats {
    vk::DeviceSize allocated = 0; // bytes in the core's allocations
    vk::DeviceSize blocks = 0;    // bytes of device memory the allocator holds
    vk::DeviceSize lazy = 0;      // bytes of lazily allocated attachments (no pages on a tiler)
    vk::DeviceSize budget = 0;    // the driver's budget over every heap (VK_EXT_memory_budget), else VMA's estimate
    vk::DeviceSize usage = 0;     // the process's usage the driver reports, where it does
};

/// The allocator.
class Memory {
public:
    [[nodiscard]] static Result<std::unique_ptr<Memory>> create(const Instance& instance,
                                                                const vk::raii::PhysicalDevice& physical,
                                                                const vk::raii::Device& device, std::uint32_t version,
                                                                bool budget);
    Memory(const Memory&) = delete;
    Memory& operator=(const Memory&) = delete;
    Memory(Memory&&) = delete;
    Memory& operator=(Memory&&) = delete;
    ~Memory();

    [[nodiscard]] Result<Image> image(const vk::ImageCreateInfo& info, MemoryUse use) const;
    [[nodiscard]] Result<Buffer> buffer(vk::DeviceSize size, vk::BufferUsageFlags usage, MemoryUse use) const;
    [[nodiscard]] MemoryStats stats() const;
    [[nodiscard]] VmaAllocator handle() const noexcept { return allocator_; }

private:
    Memory() = default;
    VmaAllocator allocator_ = nullptr;
};

} // namespace atlas::gpu
