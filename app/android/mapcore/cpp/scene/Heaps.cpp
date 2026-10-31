// The geometry heaps (scene/Heaps.hpp).
#include "scene/Heaps.hpp"

#include "base/assert.hpp"
#include "base/trace.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>
#include <ranges>

namespace atlas::scene {

namespace {

constexpr vk::BufferUsageFlags heap_usage =
    vk::BufferUsageFlagBits::eStorageBuffer | vk::BufferUsageFlagBits::eVertexBuffer |
    vk::BufferUsageFlagBits::eIndexBuffer | vk::BufferUsageFlagBits::eTransferSrc |
    vk::BufferUsageFlagBits::eTransferDst;

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

HeapBudget heap_budget(const gpu::Device& device) noexcept {
    const gpu::Facts& f = device.facts();
    constexpr vk::DeviceSize least = std::uint64_t{64} << 20U;
    constexpr vk::DeviceSize most = std::uint64_t{256} << 20U;
    vk::DeviceSize memory = 0;
    for (std::uint32_t i = 0; i < f.memory.memoryHeapCount; ++i) {
        const vk::MemoryHeap& h = f.memory.memoryHeaps.at(i);
        if (h.flags & vk::MemoryHeapFlagBits::eDeviceLocal) memory = std::max(memory, h.size);
    }
    vk::DeviceSize total = std::min(vk::DeviceSize{f.limits.maxStorageBufferRange}, most);
    if (memory > 0) total = std::min(total, memory / 16);
    return {.total = std::bit_floor(std::max(total, least))};
}

gpu::Result<std::unique_ptr<Heaps>> Heaps::create(const gpu::Device& device, const HeapBudget& budget) {
    std::unique_ptr<Heaps> self(new Heaps(device, budget)); // NOLINT(cppcoreguidelines-owning-memory)
    ATLAS_GPU_LET(buffer,
                  device.memory().buffer(std::min(budget.initial, budget.total), heap_usage, gpu::MemoryUse::upload));
    self->buffer_ = std::move(buffer);
    self->current_ = self->buffer_.handle();
    self->capacity_.store(self->buffer_.size(), std::memory_order_release);
    self->pieces_.emplace(0, budget.total);
    return self;
}

Heaps::~Heaps() = default;

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

std::optional<HeapRange> Heaps::place(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment, HeapEnd from) {
    const vk::DeviceSize mask = vk::DeviceSize{alignment} - 1;
    // The piece nearest its end that holds it (first fit by address from that end), the range at the piece's side
    // toward that end: a window's from the low offsets, a level's from the high, so each end's ranges lie together and
    // the bytes free gather between the two. Best fit, the smallest piece that holds it on its end's side, left the
    // windows' free bytes in pieces between them as they went and came in a pinch: the Westerkerk back at 5 km from
    // 12 km, 12.4 MB free and no piece of 3.8 MB for its 23rd window's walls. (VMA's default took a piece of a larger
    // size first, so a level's 15 to 27 MB pieces went in parts to small ranges, and after the zoom out from the
    // Westerkerk, 5 to 20 km, the densest far tile's 14.6 MB of ground found no piece though 56 MB were free.)
    const std::pair<const vk::DeviceSize, vk::DeviceSize>* piece = nullptr;
    vk::DeviceSize offset = 0;
    const auto fits = [&](const std::pair<const vk::DeviceSize, vk::DeviceSize>& p) {
        if (p.second < bytes) return false;
        const vk::DeviceSize o = from == HeapEnd::low ? (p.first + mask) & ~mask : (p.first + p.second - bytes) & ~mask;
        if (o < p.first || o + bytes > p.first + p.second) return false;
        piece = &p;
        offset = o;
        return true;
    };
    if (from == HeapEnd::low) {
        (void)std::ranges::find_if(pieces_, fits);
    } else {
        (void)std::ranges::find_if(std::views::reverse(pieces_), fits);
    }
    if (piece == nullptr) return std::nullopt;
    const vk::DeviceSize first = piece->first;
    const vk::DeviceSize last = piece->first + piece->second;
    pieces_.erase(first);
    if (offset > first) pieces_.emplace(first, offset - first);
    if (offset + bytes < last) pieces_.emplace(offset + bytes, last - (offset + bytes));
    used_[static_cast<std::size_t>(kind)] += bytes;
    ++ranges_[static_cast<std::size_t>(kind)];
    return HeapRange{.kind = kind, .offset = static_cast<std::uint32_t>(offset), .size = bytes};
}

void Heaps::unplace(const HeapRange& range) {
    vk::DeviceSize first = range.offset;
    vk::DeviceSize last = first + range.size;
    // joined to the pieces either side
    auto next = pieces_.lower_bound(first);
    if (next != pieces_.end() && next->first == last) {
        last += next->second;
        next = pieces_.erase(next);
    }
    if (next != pieces_.begin()) {
        const auto before = std::prev(next);
        if (before->first + before->second == first) {
            first = before->first;
            pieces_.erase(before);
        }
    }
    pieces_.emplace(first, last - first);
    used_[static_cast<std::size_t>(range.kind)] -= range.size;
    --ranges_[static_cast<std::size_t>(range.kind)];
}

gpu::Result<HeapRange> Heaps::allocate(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment, HeapEnd from) {
    ATLAS_ASSERT(std::has_single_bit(alignment), "a heap range's alignment is a power of two");
    if (bytes == 0) return HeapRange{.kind = kind, .offset = 0, .size = 0};
    HeapRange range;
    {
        const std::scoped_lock lock(blocks_lock_);
        const std::optional<HeapRange> placed = place(kind, bytes, alignment, from);
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
    return hold(range);
}

std::optional<HeapRange> Heaps::reserve(HeapKind kind, std::uint32_t bytes, std::uint32_t alignment, HeapEnd from) {
    ATLAS_ASSERT(std::has_single_bit(alignment), "a heap range's alignment is a power of two");
    if (bytes == 0) return std::nullopt;
    const std::scoped_lock lock(blocks_lock_);
    return place(kind, bytes, alignment, from);
}

gpu::Result<HeapRange> Heaps::claim(const HeapRange& range) {
    if (vk::DeviceSize{range.offset} + range.size <= capacity_.load(std::memory_order_acquire)) return range;
    return hold(range);
}

gpu::Result<HeapRange> Heaps::hold(const HeapRange& range) {
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

void Heaps::give_back(const HeapRange& range) {
    if (!range) return;
    const std::scoped_lock lock(blocks_lock_);
    unplace(range);
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

vk::DeviceSize Heaps::free_bytes() const {
    const std::scoped_lock lock(blocks_lock_);
    vk::DeviceSize used = 0;
    for (const vk::DeviceSize u : used_) used += u;
    return used < budget_.total ? budget_.total - used : 0;
}

vk::DeviceSize Heaps::largest_free() const {
    const std::scoped_lock lock(blocks_lock_);
    vk::DeviceSize largest = 0;
    for (const auto& p : pieces_) largest = std::max(largest, p.second);
    return largest;
}

vk::DeviceSize Heaps::largest_with(std::span<const HeapRange> back) const { return runs(back, false); }

vk::DeviceSize Heaps::joined_with(std::span<const HeapRange> back) const { return runs(back, true); }

vk::DeviceSize Heaps::runs(std::span<const HeapRange> back, bool joined) const {
    struct Going {
        vk::DeviceSize first = 0;
        vk::DeviceSize last = 0;
        bool back = false; // one of `back`'s, not a retired range
    };
    std::vector<Going> going;
    {
        const std::scoped_lock lock(render_lock_);
        for (const Retired& r : retired_) {
            going.push_back({.first = r.range.offset, .last = vk::DeviceSize{r.range.offset} + r.range.size});
        }
    }
    for (const HeapRange& r : back) {
        if (r) going.push_back({.first = r.offset, .last = vk::DeviceSize{r.offset} + r.size, .back = true});
    }
    std::ranges::sort(going, [](const Going& a, const Going& b) { return a.first < b.first; });
    const std::scoped_lock lock(blocks_lock_);
    vk::DeviceSize largest = 0;
    if (!joined) {
        for (const auto& p : pieces_) largest = std::max(largest, p.second);
    }
    // Each run of ranges coming back, with the pieces before, between and after them.
    for (std::size_t i = 0; i < going.size();) {
        vk::DeviceSize first = going[i].first;
        vk::DeviceSize last = going[i].last;
        bool mine = going[i].back;
        ++i;
        if (const auto next = pieces_.lower_bound(first); next != pieces_.begin()) {
            const auto before = std::prev(next);
            if (before->first + before->second == first) first = before->first;
        }
        for (;;) {
            if (const auto p = pieces_.find(last); p != pieces_.end()) {
                last += p->second;
            } else if (i < going.size() && going[i].first <= last) {
                last = std::max(last, going[i].last);
                mine = mine || going[i].back;
                ++i;
            } else {
                break;
            }
        }
        if (mine || !joined) largest = std::max(largest, last - first);
    }
    return largest;
}

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
        for (const Retired& r : retired_) s.retired_bytes += r.range.size;
        s.old_buffers = old_.size();
    }
    return s;
}

} // namespace atlas::scene
