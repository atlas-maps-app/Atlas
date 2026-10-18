// The geometry heaps (scene/Heaps.hpp).
#include "scene/Heaps.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>

namespace atlas::scene {

static_assert(sizeof(VmaVirtualAllocation) == sizeof(std::uint64_t), "the core runs on 64-bit ABIs only");

namespace {

constexpr vk::BufferUsageFlags heap_usage =
    vk::BufferUsageFlagBits::eStorageBuffer | vk::BufferUsageFlagBits::eVertexBuffer |
    vk::BufferUsageFlagBits::eIndexBuffer | vk::BufferUsageFlagBits::eTransferSrc |
    vk::BufferUsageFlagBits::eTransferDst;

[[nodiscard]] VmaVirtualAllocation allocation_of(std::uint64_t handle) noexcept {
    return std::bit_cast<VmaVirtualAllocation>(handle);
}

/// A virtual block of `size` bytes.
[[nodiscard]] gpu::Result<VmaVirtualBlock> make_block(vk::DeviceSize size) {
    const VmaVirtualBlockCreateInfo info{.size = size, .flags = 0, .pAllocationCallbacks = nullptr};
    VmaVirtualBlock block = nullptr;
    if (const VkResult r = vmaCreateVirtualBlock(&info, &block); r != VK_SUCCESS) {
        return gpu::fail(static_cast<vk::Result>(r), "a geometry heap's virtual block");
    }
    return block;
}

} // namespace

std::string_view name_of(HeapKind kind) noexcept {
    switch (kind) {
    case HeapKind::points: return "points";
    case HeapKind::walls: return "walls";
    case HeapKind::ground: return "ground";
    case HeapKind::lines: return "lines";
    case HeapKind::instances: return "instances";
    case HeapKind::labels: return "labels";
    }
    return "?";
}

vk::DeviceSize HeapStats::total_capacity() const noexcept {
    vk::DeviceSize n = 0;
    for (const vk::DeviceSize c : capacity) n += c;
    return n;
}

vk::DeviceSize HeapStats::total_used() const noexcept {
    vk::DeviceSize n = 0;
    for (const vk::DeviceSize c : used) n += c;
    return n;
}

gpu::Result<std::unique_ptr<Heaps>> Heaps::create(const gpu::Device& device, const HeapBudget& budget) {
    std::unique_ptr<Heaps> self(new Heaps(device, budget)); // NOLINT(cppcoreguidelines-owning-memory)
    for (std::size_t k = 0; k < heap_kinds; ++k) {
        Heap& heap = self->heaps_[k];
        ATLAS_GPU_LET(buffer, device.memory().buffer(budget.initial[k], heap_usage, gpu::MemoryUse::upload));
        heap.buffer = std::move(buffer);
        ATLAS_GPU_LET(block, make_block(budget.initial[k]));
        heap.blocks.push_back(block);
        heap.starts.push_back(0);
    }
    return self;
}

Heaps::~Heaps() {
    for (Heap& heap : heaps_) {
        // Every range goes with its blocks (VMA asserts on a block with live allocations: clear them first).
        for (VmaVirtualBlock block : heap.blocks) {
            vmaClearVirtualBlock(block);
            vmaDestroyVirtualBlock(block);
        }
    }
}

vk::DeviceSize Heaps::total_capacity() const {
    vk::DeviceSize n = 0;
    for (const Heap& heap : heaps_) n += heap.buffer.size();
    return n;
}

gpu::Result<void> Heaps::grow(Heap& heap, HeapKind kind, vk::DeviceSize at_least) {
    const vk::DeviceSize old_size = heap.buffer.size();
    const vk::DeviceSize others = total_capacity() - old_size;
    vk::DeviceSize size = std::max(old_size * 2, old_size + at_least);
    if (others + size > budget_.total) size = budget_.total - others;
    if (size < old_size + at_least) {
        return gpu::fail(std::format("the {} heap cannot grow by {} bytes: the heaps hold {} of their {} bytes",
                                     name_of(kind), at_least, total_capacity(), budget_.total));
    }
    ATLAS_GPU_LET(buffer, device_.memory().buffer(size, heap_usage, gpu::MemoryUse::upload));
    // The old bytes at the same offsets, so every range keeps its place; the new space a block of its own.
    std::memcpy(buffer.bytes().data(), heap.buffer.bytes().data(), static_cast<std::size_t>(old_size));
    buffer.flush();
    ATLAS_GPU_LET(block, make_block(size - old_size));
    heap.blocks.push_back(block);
    heap.starts.push_back(old_size);
    {
        const std::scoped_lock lock(render_lock_);
        old_.push_back(OldBuffer{.buffer = std::move(heap.buffer), .frame = ~std::uint64_t{0}});
    }
    heap.buffer = std::move(buffer);
    version_.fetch_add(1, std::memory_order_release);
    growths_.fetch_add(1, std::memory_order_relaxed);
    return {};
}

gpu::Result<HeapRange> Heaps::allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment) {
    ATLAS_ASSERT(std::has_single_bit(alignment), "a heap range's alignment is a power of two");
    if (bytes == 0) return HeapRange{.kind = kind, .block = 0, .offset = 0, .size = 0, .allocation = 0};
    Heap& heap = heaps_[static_cast<std::size_t>(kind)];
    const VmaVirtualAllocationCreateInfo info{.size = bytes, .alignment = alignment, .flags = 0, .pUserData = nullptr};
    auto attempt = [&]() -> std::optional<HeapRange> {
        for (std::size_t b = 0; b < heap.blocks.size(); ++b) {
            VmaVirtualAllocation allocation = nullptr;
            VkDeviceSize offset = 0;
            if (vmaVirtualAllocate(heap.blocks[b], &info, &allocation, &offset) == VK_SUCCESS) {
                heap.used += bytes;
                ++heap.ranges;
                return HeapRange{.kind = kind,
                                 .block = static_cast<std::uint32_t>(b),
                                 .offset = static_cast<std::uint32_t>(heap.starts[b] + offset),
                                 .size = bytes,
                                 .allocation = std::bit_cast<std::uint64_t>(allocation)};
            }
        }
        return std::nullopt;
    };
    {
        const std::scoped_lock lock(heap.blocks_lock);
        if (auto r = attempt()) return *r;
    }
    // Full: grow, with no writer active (the exclusive lock), unless another thread grew it meanwhile.
    const std::unique_lock exclusive(heap.lock);
    const std::scoped_lock lock(heap.blocks_lock);
    if (auto r = attempt()) return *r;
    ATLAS_GPU_CHECK(grow(heap, kind, vk::DeviceSize{bytes} + alignment));
    if (auto r = attempt()) return *r;
    return gpu::fail(std::format("a range of {} bytes in the {} heap", bytes, name_of(kind)));
}

Heaps::Writer Heaps::writer(const HeapRange& range) {
    Heap& heap = heaps_[static_cast<std::size_t>(range.kind)];
    return {heap.lock, heap.buffer, range.offset, range.size};
}

void Heaps::write(const HeapRange& range, std::span<const std::byte> data, std::uint32_t at) {
    ATLAS_ASSERT(at <= range.size && data.size() <= range.size - at, "a write beyond its heap range");
    const Writer w = writer(range);
    std::memcpy(w.bytes().data() + at, data.data(), data.size());
}

void Heaps::flush(HeapKind kind) {
    Heap& heap = heaps_[static_cast<std::size_t>(kind)];
    const std::shared_lock lock(heap.lock);
    heap.buffer.flush();
}

void Heaps::retire(const HeapRange& range, std::uint64_t frame) {
    if (!range) return;
    const std::scoped_lock lock(render_lock_);
    retired_.push_back({.range = range, .frame = frame});
}

void Heaps::bound(std::uint64_t frame) {
    const std::scoped_lock lock(render_lock_);
    for (OldBuffer& o : old_) {
        if (o.frame == ~std::uint64_t{0}) o.frame = frame == 0 ? 0 : frame - 1;
    }
}

void Heaps::collect(std::uint64_t finished) {
    std::vector<HeapRange> free_now;
    {
        const std::scoped_lock lock(render_lock_);
        std::erase_if(retired_, [&](const Retired& r) {
            if (r.frame > finished) return false;
            free_now.push_back(r.range);
            return true;
        });
        std::erase_if(old_, [&](const OldBuffer& o) { return o.frame <= finished; });
    }
    for (const HeapRange& r : free_now) {
        Heap& heap = heaps_[static_cast<std::size_t>(r.kind)];
        const std::scoped_lock lock(heap.blocks_lock);
        vmaVirtualFree(heap.blocks[r.block], allocation_of(r.allocation));
        heap.used -= r.size;
        --heap.ranges;
    }
}

vk::Buffer Heaps::buffer(HeapKind kind) const {
    const Heap& heap = heaps_[static_cast<std::size_t>(kind)];
    const std::shared_lock lock(heap.lock);
    return heap.buffer.handle();
}

vk::DeviceSize Heaps::capacity(HeapKind kind) const {
    const Heap& heap = heaps_[static_cast<std::size_t>(kind)];
    const std::shared_lock lock(heap.lock);
    return heap.buffer.size();
}

std::uint64_t Heaps::version() const noexcept { return version_.load(std::memory_order_acquire); }

HeapStats Heaps::stats() const {
    HeapStats s;
    for (std::size_t k = 0; k < heap_kinds; ++k) {
        const Heap& heap = heaps_[k];
        const std::shared_lock lock(heap.lock);
        const std::scoped_lock blocks(heap.blocks_lock);
        s.capacity[k] = heap.buffer.size();
        s.used[k] = heap.used;
        s.ranges[k] = heap.ranges;
    }
    s.growths = growths_.load(std::memory_order_relaxed);
    {
        const std::scoped_lock lock(render_lock_);
        s.retired = retired_.size();
    }
    return s;
}

} // namespace atlas::scene
