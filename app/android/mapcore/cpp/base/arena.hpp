// Memory without per-item heap traffic (plan-vector T3, T7): a monotonic arena for per-tile build data and per-frame
// lists, freed all at once, and a vector of fixed capacity for lists whose size has a hard cap.
#pragma once

#include "base/assert.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <memory_resource>
#include <new>
#include <span>
#include <type_traits>
#include <utility>
#include <vector>

namespace atlas {

/// What an arena does when its first block is full: a frame arena stops (a broken budget is a bug), a tile arena grows.
enum class Growth : unsigned char { fixed, grows };

/// A monotonic memory resource: allocation bumps a pointer, deallocation is free, reset() frees everything at once.
/// Use it directly or through std::pmr containers. Not thread-safe: one arena per worker or per frame.
class Arena final : public std::pmr::memory_resource {
public:
    /// An arena whose first block holds `capacity` bytes; `name` appears in the failure when a fixed arena overflows.
    explicit Arena(std::size_t capacity, Growth growth = Growth::fixed, const char* name = "arena")
        : first_(std::make_unique_for_overwrite<std::byte[]>(capacity)), first_size_(capacity), growth_(growth),
          name_(name) {
        ATLAS_ASSERT(capacity > 0, "an arena needs room");
        rewind(first_.get(), first_size_);
    }

    Arena(const Arena&) = delete;
    Arena& operator=(const Arena&) = delete;
    Arena(Arena&&) = delete;
    Arena& operator=(Arena&&) = delete;
    ~Arena() override = default;

    /// Frees every allocation at once and keeps the first block; blocks grown since are returned to the heap.
    void reset() noexcept {
        extra_.clear();
        rewind(first_.get(), first_size_);
        used_ = 0;
    }

    /// Bytes handed out since the last reset, alignment padding included.
    [[nodiscard]] std::size_t used() const noexcept { return used_; }

    /// The most bytes in use at once since the arena was made.
    [[nodiscard]] std::size_t peak() const noexcept { return peak_; }

    /// The first block's size.
    [[nodiscard]] std::size_t capacity() const noexcept { return first_size_; }

    /// Blocks grown beyond the first since the last reset.
    [[nodiscard]] std::size_t extra_blocks() const noexcept { return extra_.size(); }

    /// Uninitialised storage for `count` records; valid until reset(). Records are trivially copyable, so the storage
    /// holds them as soon as they are written (their lifetime starts implicitly).
    template <class T>
        requires std::is_trivially_copyable_v<T> && std::is_trivially_destructible_v<T>
    [[nodiscard]] std::span<T> allocate_array(std::size_t count) {
        ATLAS_ASSERT(count <= std::numeric_limits<std::size_t>::max() / sizeof(T), "array size overflows");
        return {static_cast<T*>(allocate(count * sizeof(T), alignof(T))), count};
    }

private:
    void* do_allocate(std::size_t bytes, std::size_t alignment) override {
        ATLAS_ASSERT(std::has_single_bit(alignment), "alignment must be a power of two");
        if (void* p = bump(bytes, alignment)) return p;
        if (growth_ == Growth::fixed) ::atlas::fail("a fixed arena is full", name_);
        // A block at least twice the first, and large enough for this request at any alignment.
        ATLAS_ASSERT(bytes <= std::numeric_limits<std::size_t>::max() - alignment, "allocation size overflows");
        const std::size_t size = std::max(first_size_ * 2, bytes + alignment);
        rewind(extra_.emplace_back(std::make_unique_for_overwrite<std::byte[]>(size)).get(), size);
        void* p = bump(bytes, alignment);
        ATLAS_ASSERT(p != nullptr, "a new arena block must fit the request");
        return p;
    }

    void do_deallocate(void* /*p*/, std::size_t /*bytes*/, std::size_t /*alignment*/) noexcept override {}

    [[nodiscard]] bool do_is_equal(const std::pmr::memory_resource& other) const noexcept override {
        return this == &other;
    }

    void rewind(std::byte* block, std::size_t size) noexcept {
        cursor_ = block;
        room_ = size;
    }

    void* bump(std::size_t bytes, std::size_t alignment) noexcept {
        const auto at = std::bit_cast<std::uintptr_t>(cursor_);
        const std::size_t padding = static_cast<std::size_t>(-at) & (alignment - 1); // to the next multiple
        if (padding > room_ || bytes > room_ - padding) return nullptr;
        const std::size_t taken = padding + bytes;
        std::byte* p = cursor_ + padding;
        cursor_ += taken;
        room_ -= taken;
        used_ += taken;
        peak_ = std::max(peak_, used_);
        return p;
    }

    std::unique_ptr<std::byte[]> first_;
    std::size_t first_size_;
    std::vector<std::unique_ptr<std::byte[]>> extra_;
    std::byte* cursor_ = nullptr;
    std::size_t room_ = 0;
    std::size_t used_ = 0;
    std::size_t peak_ = 0;
    Growth growth_;
    const char* name_;
};

/// A vector of at most N records stored inline, for per-frame lists with a hard cap (the subset of C++26's
/// std::inplace_vector the core needs, until libc++ ships it). Records are trivially copyable: lists are data.
template <class T, std::size_t N>
    requires std::is_trivially_copyable_v<T> && std::is_trivially_destructible_v<T>
class InplaceVector { // NOLINT(cppcoreguidelines-pro-type-member-init): bytes_ is uninitialised storage on purpose
public:
    using value_type = T;
    using size_type = std::size_t;
    using iterator = T*;
    using const_iterator = const T*;

    [[nodiscard]] static constexpr std::size_t capacity() noexcept { return N; }
    [[nodiscard]] std::size_t size() const noexcept { return size_; }
    [[nodiscard]] bool empty() const noexcept { return size_ == 0; }
    [[nodiscard]] bool full() const noexcept { return size_ == N; }

    [[nodiscard]] T* data() noexcept { return std::launder(reinterpret_cast<T*>(bytes_.data())); }
    [[nodiscard]] const T* data() const noexcept { return std::launder(reinterpret_cast<const T*>(bytes_.data())); }

    [[nodiscard]] T* begin() noexcept { return data(); }
    [[nodiscard]] T* end() noexcept { return data() + size_; }
    [[nodiscard]] const T* begin() const noexcept { return data(); }
    [[nodiscard]] const T* end() const noexcept { return data() + size_; }

    [[nodiscard]] T& operator[](std::size_t i) noexcept {
        ATLAS_DEBUG_ASSERT(i < size_, "index out of range");
        return data()[i];
    }
    [[nodiscard]] const T& operator[](std::size_t i) const noexcept {
        ATLAS_DEBUG_ASSERT(i < size_, "index out of range");
        return data()[i];
    }

    [[nodiscard]] std::span<T> span() noexcept { return {data(), size_}; }
    [[nodiscard]] std::span<const T> span() const noexcept { return {data(), size_}; }

    /// Appends a record when there is room and returns it, else returns nullptr
    /// (std::inplace_vector::try_emplace_back).
    template <class... Args>
        requires std::constructible_from<T, Args...>
    T* try_emplace_back(Args&&... args) noexcept {
        if (size_ == N) return nullptr;
        void* slot = bytes_.data() + (size_ * sizeof(T));
        T* record = std::construct_at(static_cast<T*>(slot), std::forward<Args>(args)...);
        ++size_;
        return record;
    }

    /// Appends a record; a full list is a broken budget and ends the process.
    template <class... Args>
        requires std::constructible_from<T, Args...>
    T& emplace_back(Args&&... args) noexcept {
        T* record = try_emplace_back(std::forward<Args>(args)...);
        ATLAS_ASSERT(record != nullptr, "an InplaceVector is full");
        return *record;
    }

    void push_back(const T& value) noexcept { emplace_back(value); }

    void pop_back() noexcept {
        ATLAS_ASSERT(size_ > 0, "pop_back on an empty InplaceVector");
        --size_;
    }

    void clear() noexcept { size_ = 0; }

private:
    // Storage for records yet to be made: left uninitialised on purpose, as a per-frame list must not pay for zeroing.
    alignas(T) std::array<std::byte, sizeof(T) * N> bytes_;
    std::size_t size_ = 0;
};

} // namespace atlas
