// The geometry heaps (scene/Heaps.hpp).
#include "scene/Heaps.hpp"

#include "base/assert.hpp"
#include "base/trace.hpp"

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

vk::DeviceSize HeapStats::total_used() const noexcept {
    vk::DeviceSize n = 0;
    for (const vk::DeviceSize c : used) n += c;
    return n;
}

gpu::Result<std::unique_ptr<Heaps>> Heaps::create(const gpu::Device& device, const HeapBudget& budget) {
    std::unique_ptr<Heaps> self(new Heaps(device, budget)); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(buffer,
                  device.memory().buffer(std::min(budget.initial, budget.total), heap_usage, gpu::MemoryUse::upload));
    self->buffer_ = std::move(buffer);
    self->current_ = self->buffer_.handle();
    self->capacity_.store(self->buffer_.size(), std::memory_order_release);
    ATLAS_GPU_LET(block, make_block(budget.total));
    self->block_ = block;
    return self;
}

Heaps::~Heaps() {
    // Every range goes with the block (VMA asserts on a block with live allocations: clear it first).
    if (block_ != nullptr) {
        vmaClearVirtualBlock(block_);
        vmaDestroyVirtualBlock(block_);
    }
}

gpu::Result<void> Heaps::grow(vk::DeviceSize end) {
    const trace::Section traced("atlas heaps grow");
    const vk::DeviceSize old_size = buffer_.size();
    const vk::DeviceSize size = std::min(std::max(old_size * 2, end), budget_.total);
    ATLAS_GPU_LET(buffer, device_.memory().buffer(size, heap_usage, gpu::MemoryUse::upload));
    // The old bytes at the same offsets, so every range keeps its place.
    std::memcpy(buffer.bytes().data(), buffer_.bytes().data(), static_cast<std::size_t>(old_size));
    buffer.flush();
    {
        // The swap alone under the render lock, after the copy: a frame binds the old buffer or the new one, never
        // waits.
        const std::scoped_lock lock(render_lock_);
        old_.push_back(OldBuffer{.buffer = std::move(buffer_), .frame = ~std::uint64_t{0}});
        buffer_ = std::move(buffer);
        current_ = buffer_.handle();
        capacity_.store(buffer_.size(), std::memory_order_release);
    }
    version_.fetch_add(1, std::memory_order_release);
    growths_.fetch_add(1, std::memory_order_relaxed);
    return {};
}

std::optional<HeapRange> Heaps::place(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment) {
    const VmaVirtualAllocationCreateInfo info{.size = bytes, .alignment = alignment, .flags = 0, .pUserData = nullptr};
    VmaVirtualAllocation allocation = nullptr;
    VkDeviceSize offset = 0;
    if (vmaVirtualAllocate(block_, &info, &allocation, &offset) != VK_SUCCESS) return std::nullopt;
    used_[static_cast<std::size_t>(kind)] += bytes;
    ++ranges_[static_cast<std::size_t>(kind)];
    return HeapRange{.kind = kind,
                     .block = 0,
                     .offset = static_cast<std::uint32_t>(offset),
                     .size = bytes,
                     .allocation = std::bit_cast<std::uint64_t>(allocation)};
}

void Heaps::unplace(const HeapRange& range) {
    vmaVirtualFree(block_, allocation_of(range.allocation));
    used_[static_cast<std::size_t>(range.kind)] -= range.size;
    --ranges_[static_cast<std::size_t>(range.kind)];
}

gpu::Result<HeapRange> Heaps::allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment) {
    ATLAS_ASSERT(std::has_single_bit(alignment), "a heap range's alignment is a power of two");
    if (bytes == 0) return HeapRange{.kind = kind, .block = 0, .offset = 0, .size = 0, .allocation = 0};
    HeapRange range;
    {
        const std::scoped_lock lock(blocks_lock_);
        const std::optional<HeapRange> placed = place(kind, bytes, alignment);
        if (!placed) {
            vk::DeviceSize used = 0;
            for (const vk::DeviceSize u : used_) used += u;
            return gpu::fail(std::format("the heaps cannot grow by {} bytes for {}: {} of their {} bytes are used",
                                         bytes, name_of(kind), used, budget_.total));
        }
        // The capacity from its atomic: buffer_ is a growth's to swap (under the exclusive lock, which this does not
        // hold). Seen too small, the range takes the slow path below, which looks again under that lock; seen grown,
        // the range is the new buffer's, which its writer reaches once the growth lets the lock go.
        if (vk::DeviceSize{placed->offset} + placed->size <= capacity_.load(std::memory_order_acquire)) return *placed;
        range = *placed;
    }
    // Beyond the buffer: it grows to hold the range, with no writer active (the exclusive lock), unless another
    // thread grew it meanwhile. The blocks' lock is not held through the copy, so the render thread's stats never wait.
    const std::unique_lock exclusive(lock_);
    const vk::DeviceSize end = vk::DeviceSize{range.offset} + range.size;
    if (end > buffer_.size()) {
        if (auto grown = grow(end); !grown) {
            const std::scoped_lock lock(blocks_lock_);
            unplace(range);
            return std::unexpected(std::move(grown.error()));
        }
    }
    return range;
}

Heaps::Writer Heaps::writer(const HeapRange& range) { return {lock_, buffer_, range.offset, range.size}; }

void Heaps::write(const HeapRange& range, std::span<const std::byte> data, std::uint32_t at) {
    ATLAS_ASSERT(at <= range.size && data.size() <= range.size - at, "a write beyond its heap range");
    const Writer w = writer(range);
    std::memcpy(w.bytes().data() + at, data.data(), data.size());
}

void Heaps::flush() {
    const std::shared_lock lock(lock_);
    buffer_.flush();
}

void Heaps::retire(const HeapRange& range, std::uint64_t frame) {
    if (!range) return;
    const std::scoped_lock lock(render_lock_);
    retired_.push_back({.range = range, .frame = frame});
}

vk::Buffer Heaps::bind(std::uint64_t frame) {
    const std::scoped_lock lock(render_lock_);
    for (OldBuffer& o : old_) {
        if (o.frame == ~std::uint64_t{0}) o.frame = frame == 0 ? 0 : frame - 1;
    }
    return current_;
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
    if (free_now.empty()) return;
    {
        const std::scoped_lock lock(blocks_lock_);
        for (const HeapRange& r : free_now) unplace(r);
    }
    frees_.fetch_add(1, std::memory_order_release);
}

vk::Buffer Heaps::buffer() const {
    const std::scoped_lock lock(render_lock_);
    return current_;
}

vk::DeviceSize Heaps::capacity() const { return capacity_.load(std::memory_order_acquire); }

std::uint64_t Heaps::version() const noexcept { return version_.load(std::memory_order_acquire); }

std::uint64_t Heaps::frees() const noexcept { return frees_.load(std::memory_order_acquire); }

HeapStats Heaps::stats() const {
    HeapStats s;
    s.capacity = capacity_.load(std::memory_order_acquire);
    {
        const std::scoped_lock blocks(blocks_lock_);
        s.used = used_;
        s.ranges = ranges_;
    }
    s.growths = growths_.load(std::memory_order_relaxed);
    {
        const std::scoped_lock lock(render_lock_);
        s.retired = retired_.size();
        s.old_buffers = old_.size();
    }
    return s;
}

} // namespace atlas::scene
