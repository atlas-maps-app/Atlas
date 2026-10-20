// The geometry heaps (plan-vector D5.7, D3 "Streaming", D5.2): one VkBuffer for every layer kind (points, walls,
// ground, lines, instances, labels), host-visible and device-local on unified-memory phones (gpu::MemoryUse::upload),
// sub-allocated per tile and kind in one VMA virtual block of the whole budget, 64 MB. The kinds share the one buffer
// and the one block, so a range finds room wherever the budget has it: with a buffer per kind, each doubling within
// the budget, the Main board at S1 found the budget spent on capacity while a tile's ground did not fit, and with a
// block per growth the densest tiles' walls (12.7 MB) found no block with room. Workers decode and build a
// tile straight into its mapped ranges (no staging copy, no upload budget per frame), and the render thread references
// only published tiles; a range a tile lets go of returns to the heap once the frames that may read it have finished
// (retire, collect: two frames later, after the fences). Shaders read the heap as storage buffers through set 1, the
// buffer bound at each kind's binding (frame/FrameLayout.hpp), a range's offset the same in all of them.
//
// The buffer starts small and doubles when a range the block places lies beyond it, within the budget: the new buffer
// takes the old one's bytes at the same offsets (so every range keeps its offset), and the old buffer goes once the
// frames bound to it have finished (bound, collect). Writers hold the heap's shared lock while they write (Writer), a
// growth its exclusive lock, so no write is lost to a growth.
//
// Thread-safe: allocate and write from any thread; retire, bound and collect on the render thread.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::scene {

/// The heaps' layer kinds (D5.7), in set 1's order (frame::Global's first six bindings).
enum class HeapKind : std::uint8_t { points = 0, walls = 1, ground = 2, lines = 3, instances = 4, labels = 5 };

/// The number of layer kinds.
inline constexpr std::size_t heap_kinds = 6;

/// The kind's name.
[[nodiscard]] std::string_view name_of(HeapKind kind) noexcept;

/// A range of the heap: its kind, where it lies, and the virtual allocation that holds it.
struct HeapRange {
    HeapKind kind = HeapKind::points;
    std::uint32_t block = 0; ///< the heap's virtual block it was allocated from (one: 0)
    std::uint32_t offset = 0;
    std::uint32_t size = 0;
    std::uint64_t allocation = 0; ///< the VmaVirtualAllocation, as a handle

    [[nodiscard]] explicit operator bool() const noexcept { return size > 0; }
};

/// What the heap's buffer starts with and what it may grow to, its virtual block's size: 64 MB, D3's 40 MB until the
/// walls' records are 32 bytes (ENGINE.md, step 2), since the canal ring's densest tile (14/8414/5384) holds 13 MB of
/// walls and its second sweep camera's tiles 50 MB in all.
struct HeapBudget {
    vk::DeviceSize initial = std::uint64_t{8} << 20U;
    vk::DeviceSize total = std::uint64_t{64} << 20U;
};

/// What the heap holds: its capacity, and its ranges' bytes and count by kind.
struct HeapStats {
    vk::DeviceSize capacity = 0;
    std::array<vk::DeviceSize, heap_kinds> used{};
    std::array<std::uint32_t, heap_kinds> ranges{};
    std::uint64_t growths = 0;
    std::uint64_t retired = 0; ///< ranges waiting for their frames to finish
    [[nodiscard]] vk::DeviceSize total_used() const noexcept;
};

/// The geometry heaps: one buffer, its ranges by kind.
class Heaps {
public:
    [[nodiscard]] static gpu::Result<std::unique_ptr<Heaps>> create(const gpu::Device& device,
                                                                    const HeapBudget& budget = {});
    Heaps(const Heaps&) = delete;
    Heaps& operator=(const Heaps&) = delete;
    Heaps(Heaps&&) = delete;
    Heaps& operator=(Heaps&&) = delete;
    ~Heaps();

    /// A range of `bytes` of kind `kind`, aligned to `alignment` (a power of two), growing the heap when it does not
    /// fit; an error when the budget does not allow it. Any thread.
    [[nodiscard]] gpu::Result<HeapRange> allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment = 16);

    /// Writes into a range in place: holds the heap's shared lock while it lives, so a growth waits for it.
    class Writer {
    public:
        Writer(const Writer&) = delete;
        Writer& operator=(const Writer&) = delete;
        Writer(Writer&&) = delete;
        Writer& operator=(Writer&&) = delete;
        ~Writer() = default;
        /// The range's mapped bytes.
        [[nodiscard]] std::span<std::byte> bytes() const noexcept { return bytes_; }

    private:
        friend class Heaps;
        /// Takes the heap's shared lock, then the range's bytes of its buffer (which no growth moves while it lives).
        Writer(std::shared_mutex& lock, const gpu::Buffer& buffer, std::uint32_t offset, std::uint32_t size)
            : lock_(lock), bytes_(buffer.bytes().subspan(offset, size)) {}
        std::shared_lock<std::shared_mutex> lock_;
        std::span<std::byte> bytes_;
    };
    /// A writer of `range`. Its writes reach the GPU once flush has run (after a tile's writes, before it publishes).
    [[nodiscard]] Writer writer(const HeapRange& range);
    /// Copies `data` into `range` from byte `at`.
    void write(const HeapRange& range, std::span<const std::byte> data, std::uint32_t at = 0);
    /// Makes the CPU's writes visible to the GPU (a no-op on coherent memory).
    void flush();

    /// Frees `range` once frame `frame` (the last that may read it) has finished on the GPU. Render thread.
    void retire(const HeapRange& range, std::uint64_t frame);
    /// Frame `frame` binds the heap as it is now (its buffer, after any growth): what a growth replaced is let go of
    /// once the frames before it have finished. Render thread.
    void bound(std::uint64_t frame);
    /// Frees the ranges and the old buffers whose frames have all finished (`finished`: the newest finished frame).
    void collect(std::uint64_t finished);

    /// The heap's buffer now (every kind's binding), its size, and its version (it changes with every growth).
    [[nodiscard]] vk::Buffer buffer() const;
    [[nodiscard]] vk::DeviceSize capacity() const;
    [[nodiscard]] std::uint64_t version() const noexcept;
    /// How many times collect has freed ranges: a range that found no room may find it once this has changed.
    [[nodiscard]] std::uint64_t frees() const noexcept;
    [[nodiscard]] HeapStats stats() const;

private:
    explicit Heaps(const gpu::Device& device, const HeapBudget& budget) noexcept : device_(device), budget_(budget) {}

    struct Retired {
        HeapRange range;
        std::uint64_t frame = 0;
    };
    struct OldBuffer {
        gpu::Buffer buffer;
        std::uint64_t frame = ~std::uint64_t{0}; // the last frame that may read it; unknown until bound
    };

    /// Grows the buffer to hold `end` bytes or more (doubling, within the budget). Under both locks.
    [[nodiscard]] gpu::Result<void> grow(vk::DeviceSize end);
    /// A range of the virtual block, or none when it has no room; and a range given back. Under blocks_lock_.
    [[nodiscard]] std::optional<HeapRange> place(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment);
    void unplace(const HeapRange& range);

    const gpu::Device& device_;
    HeapBudget budget_;
    mutable std::shared_mutex lock_; // shared: writers; exclusive: a growth
    mutable std::mutex blocks_lock_; // the virtual block (VMA's are not thread-safe), used_ and ranges_
    gpu::Buffer buffer_;
    VmaVirtualBlock block_ = nullptr; // the budget's offsets; the buffer covers those placed
    std::array<vk::DeviceSize, heap_kinds> used_{};
    std::array<std::uint32_t, heap_kinds> ranges_{};
    mutable std::mutex render_lock_; // retired_ and old_
    std::vector<Retired> retired_;
    std::vector<OldBuffer> old_;
    std::atomic<std::uint64_t> version_{0};
    std::atomic<std::uint64_t> growths_{0};
    std::atomic<std::uint64_t> frees_{0};
};

} // namespace atlas::scene
