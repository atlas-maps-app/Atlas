// The geometry heaps (plan-vector D5.7, D3 "Streaming", D5.2): one VkBuffer for every layer kind (points, walls,
// ground, lines, instances, labels), host-visible and device-local on unified-memory phones (gpu::MemoryUse::upload),
// sub-allocated per tile and kind over the whole budget, 64 MB. The kinds share the one buffer and its one span of
// offsets, so a range finds room wherever the budget has it: with a buffer per kind, each doubling within the budget,
// the Main board at S1 found the budget spent on capacity while a tile's ground did not fit, and with a block per
// growth the densest tiles' walls (12.7 MB) found no block with room. A window's ranges are placed from the budget's
// start and a level's from its end, each in the piece nearest its end that holds it (HeapEnd), so the levels a camera
// leaves behind coming within 9 km give their bytes back in one piece: placed among the windows, they left pieces
// between them too small for a window's walls (Alkmaar after a pinch out to 12 km and back, 37.7 MB free and none of
// 6.05 MB). Workers decode and build a tile straight
// into its mapped ranges (no staging copy, no upload budget per frame), and the render thread references only
// published tiles; a range a tile lets go of returns to the heap once the frames that may read it have finished
// (retire, collect: two frames later, after the fences). Shaders read the heap as storage buffers through set 1, the
// buffer bound at each kind's binding (frame/FrameLayout.hpp), a range's offset the same in all of them.
//
// The buffer starts small and doubles when a range placed lies beyond it, within the budget (a level's first range
// grows it to the budget, at its end): the new buffer takes the old one's bytes at the same offsets (so every range
// keeps its offset), and the old buffer goes once the frames bound to it have finished (bind, collect). Writers hold
// the heap's shared lock while they write (Writer), a growth its exclusive lock, so no write is lost to a growth. The
// render thread never waits for a growth (ENGINE.md's gain 16): it takes the buffer to bind and lets go of the replaced
// ones under the render lock alone, which a growth takes only to swap the buffers, after its copy; its stats read the
// capacity from an atomic and the ranges under the blocks' lock, which a growth does not hold.
//
// Thread-safe: allocate and write from any thread; retire, bind and collect on the render thread.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <atomic>
#include <cstdint>
#include <map>
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

/// Which end of the budget a range is placed from: a window's (a tile of the finest level, L14, drawn within 4.7 km of
/// the eye) from the low offsets, a level's (a frame cell's L13 or L12, a region's L12: drawn beyond) from the high.
enum class HeapEnd : std::uint8_t { low = 0, high = 1 };

/// A range of the heap: its kind and where it lies.
struct HeapRange {
    HeapKind kind = HeapKind::points;
    std::uint32_t offset = 0;
    std::uint32_t size = 0;

    [[nodiscard]] explicit operator bool() const noexcept { return size > 0; }
};

/// What the heap's buffer starts with and what it may grow to, its virtual block's size: 64 MB, D3's 40 MB until the
/// walls' records are 32 bytes (ENGINE.md, step 2), since the canal ring's densest tile (14/8414/5384) holds 13 MB of
/// walls and its second sweep camera's tiles 50 MB in all.
struct HeapBudget {
    vk::DeviceSize initial = std::uint64_t{8} << 20U;
    vk::DeviceSize total = std::uint64_t{64} << 20U;
};

/// The app's map's budget on `device`: as much as one storage buffer may bind (limits.maxStorageBufferRange: 128 MB on
/// every PowerVR and most Adrenos of the device survey, up to 4 GB elsewhere), at least 64 MB and at most 256 MB, and
/// no more than a sixteenth of the largest device-local memory heap; a power of two, as the buffer doubles up to it. 64
/// MB filled with the canal ring zoomed out and the windows around it (61 of 64 MB at 1.6 km), where the tiles a pinch
/// zooms in to then waited for room the drawn ones did not give up (the user, 28 October, on alpha 17: "At round z 17.0
/// I cant zoom in any further").
[[nodiscard]] HeapBudget heap_budget(const gpu::Device& device) noexcept;

/// What the heap holds: its capacity, and its ranges' bytes and count by kind.
struct HeapStats {
    vk::DeviceSize capacity = 0;
    std::array<vk::DeviceSize, heap_kinds> used{};
    std::array<std::uint32_t, heap_kinds> ranges{};
    std::uint64_t growths = 0;
    std::uint64_t retired = 0;        ///< ranges waiting for their frames to finish
    vk::DeviceSize retired_bytes = 0; ///< and their bytes
    std::uint64_t old_buffers = 0;    ///< buffers a growth replaced, kept until the frames bound to them finish
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

    /// A range of `bytes` of kind `kind`, aligned to `alignment` (a power of two), placed from `from`: in the piece
    /// nearest that end that holds it (first fit by address), at the piece's side toward it; growing the heap when it
    /// lies beyond the buffer; an error when no piece holds it. Any thread.
    [[nodiscard]] gpu::Result<HeapRange> allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment = 16,
                                                  HeapEnd from = HeapEnd::low);
    /// A range placed as allocate places it, without growing the buffer (the render thread's: it never waits for a
    /// growth's copy), or none; claim makes it the buffer's on the thread that writes it. The residency's room for a
    /// tile tried again (Residency::want), so a build beside it does not take its piece first.
    [[nodiscard]] std::optional<HeapRange> reserve(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment,
                                                   HeapEnd from);
    /// A reserved range, the buffer grown to hold it when it lies beyond; an error (the range given back) when the
    /// budget does not allow it. Any thread.
    [[nodiscard]] gpu::Result<HeapRange> claim(const HeapRange& range);

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
    /// Frees `range` at once, which no frame has drawn (a tile whose build failed lets its ranges go), and not as one
    /// of frees(): the tile that found no room would take its own ranges' going back for room and be built again,
    /// and again (Alkmaar's L13 tile 13/4205/2681 from 5 km, every other frame at rest). Any thread.
    void give_back(const HeapRange& range);
    /// Frame `frame` binds the heap as it is now: the buffer it binds, taken with the marking of what a growth replaced
    /// (let go of once the frames before `frame` have finished), so no growth falls between the two. Render thread; it
    /// never waits for a growth's copy.
    [[nodiscard]] vk::Buffer bind(std::uint64_t frame);
    /// bind without the buffer.
    void bound(std::uint64_t frame) { (void)bind(frame); }
    /// Frees the ranges and the old buffers whose frames have all finished (`finished`: the newest finished frame).
    void collect(std::uint64_t finished);

    /// The heap's buffer now (every kind's binding), its size, and its version (it changes with every growth).
    [[nodiscard]] vk::Buffer buffer() const;
    [[nodiscard]] vk::DeviceSize capacity() const;
    [[nodiscard]] std::uint64_t version() const noexcept;
    /// How many times collect has freed ranges: a range that found no room may find it once this has changed.
    [[nodiscard]] std::uint64_t frees() const noexcept;
    /// The budget's bytes no range holds (a retired range's among those held until it is freed). Any thread.
    [[nodiscard]] vk::DeviceSize free_bytes() const;
    /// The largest piece of the budget no range holds: a larger range finds no room whatever the bytes free (their
    /// pieces too small for it). Any thread; it walks the pieces.
    [[nodiscard]] vk::DeviceSize largest_free() const;
    /// The largest piece there would be with `back` and the retired ranges freed too, each joined to the pieces
    /// beside it: which tiles' going makes room for a range that finds no piece (World::make_room). Any thread.
    [[nodiscard]] vk::DeviceSize largest_with(std::span<const HeapRange> back) const;
    /// The largest of those pieces that `back` joins: how near a tile's going comes to making room. Any thread.
    [[nodiscard]] vk::DeviceSize joined_with(std::span<const HeapRange> back) const;
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
    /// A range of the budget's offsets, or none when no piece holds it; and a range given back, joined to the pieces
    /// beside it. Under blocks_lock_.
    [[nodiscard]] std::optional<HeapRange> place(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment,
                                                 HeapEnd from);
    void unplace(const HeapRange& range);
    /// largest_with (joined: of the pieces `back` joins alone).
    [[nodiscard]] vk::DeviceSize runs(std::span<const HeapRange> back, bool joined) const;
    /// Grows the buffer to hold `range` (allocate's slow path); the range given back when it cannot.
    [[nodiscard]] gpu::Result<HeapRange> hold(const HeapRange& range);

    const gpu::Device& device_;
    HeapBudget budget_;
    mutable std::shared_mutex lock_; // shared: writers; exclusive: a growth
    mutable std::mutex blocks_lock_; // the pieces, used_ and ranges_
    gpu::Buffer buffer_;             // under lock_; swapped by a growth under render_lock_ too
    // The budget's offsets no range holds, by offset: their sizes (no two touching).
    std::map<vk::DeviceSize, vk::DeviceSize> pieces_;
    std::array<vk::DeviceSize, heap_kinds> used_{};
    std::array<std::uint32_t, heap_kinds> ranges_{};
    mutable std::mutex render_lock_; // retired_, old_ and current_
    std::vector<Retired> retired_;
    std::vector<OldBuffer> old_;
    vk::Buffer current_;                      // buffer_'s handle, for the render thread
    std::atomic<vk::DeviceSize> capacity_{0}; // buffer_'s size, for the stats
    std::atomic<std::uint64_t> version_{0};
    std::atomic<std::uint64_t> growths_{0};
    std::atomic<std::uint64_t> frees_{0};
};

} // namespace atlas::scene
