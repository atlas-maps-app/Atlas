// Where a pack's bytes come from (plan-vector D3, T8): memory the core maps (a pack file, or a bundled pack stored
// uncompressed in the APK, mapped through the asset's file descriptor and offset), or byte ranges fetched on demand
// (the world mid-zoom base's release assets, read a chunk at a time over HTTP by the app, K20b). Sources are read on
// the I/O thread and the workers; every implementation is safe to read from several threads at once.
#pragma once

#include "tiles/Error.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace atlas::tiles {

/// A pack's bytes.
class ByteSource {
public:
    ByteSource() = default;
    ByteSource(const ByteSource&) = delete;
    ByteSource& operator=(const ByteSource&) = delete;
    ByteSource(ByteSource&&) = delete;
    ByteSource& operator=(ByteSource&&) = delete;
    virtual ~ByteSource() = default;

    /// The pack's size in bytes.
    [[nodiscard]] virtual std::uint64_t size() const noexcept = 0;

    /// The bytes [offset, offset + length) in place, valid while the source lives, when the source is mapped; empty
    /// for a source that must copy (and for a range outside the pack).
    [[nodiscard]] virtual std::span<const std::byte> view(std::uint64_t offset, std::uint64_t length) const noexcept;

    /// Copies the bytes [offset, offset + out.size()) into `out`.
    [[nodiscard]] virtual Result<void> read(std::uint64_t offset, std::span<std::byte> out) const = 0;

    /// The bytes [offset, offset + length): in place when mapped, else copied into `scratch`.
    [[nodiscard]] Result<std::span<const std::byte>> bytes(std::uint64_t offset, std::uint64_t length,
                                                           std::vector<std::byte>& scratch) const;
};

/// Bytes the source owns, or views (tests, fuzzing, a pack already in memory).
class MemorySource final : public ByteSource {
public:
    /// Owns `bytes`.
    explicit MemorySource(std::vector<std::byte> bytes) noexcept : owned_(std::move(bytes)), bytes_(owned_) {}
    /// Views `bytes`, which must outlive the source.
    explicit MemorySource(std::span<const std::byte> bytes) noexcept : bytes_(bytes) {}

    [[nodiscard]] std::uint64_t size() const noexcept override { return bytes_.size(); }
    [[nodiscard]] std::span<const std::byte> view(std::uint64_t offset, std::uint64_t length) const noexcept override;
    [[nodiscard]] Result<void> read(std::uint64_t offset, std::span<std::byte> out) const override;

private:
    std::vector<std::byte> owned_;
    std::span<const std::byte> bytes_;
};

/// A file, or a range of one (an APK asset through its descriptor's offset and length), mapped read-only.
class MappedSource final : public ByteSource {
public:
    /// Maps the file at `path`.
    [[nodiscard]] static Result<std::unique_ptr<MappedSource>> open(const std::string& path);

    /// Maps `length` bytes of the open file `fd` from `offset`; the mapping outlives the descriptor, which stays the
    /// caller's.
    [[nodiscard]] static Result<std::unique_ptr<MappedSource>> open(int fd, std::uint64_t offset, std::uint64_t length);

    MappedSource(const MappedSource&) = delete;
    MappedSource& operator=(const MappedSource&) = delete;
    MappedSource(MappedSource&&) = delete;
    MappedSource& operator=(MappedSource&&) = delete;
    ~MappedSource() override;

    [[nodiscard]] std::uint64_t size() const noexcept override { return bytes_.size(); }
    [[nodiscard]] std::span<const std::byte> view(std::uint64_t offset, std::uint64_t length) const noexcept override;
    [[nodiscard]] Result<void> read(std::uint64_t offset, std::span<std::byte> out) const override;

private:
    MappedSource(void* map, std::size_t map_length, std::span<const std::byte> bytes) noexcept
        : map_(map), map_length_(map_length), bytes_(bytes) {}

    void* map_ = nullptr;
    std::size_t map_length_ = 0;
    std::span<const std::byte> bytes_;
};

/// Byte ranges fetched on demand: `fetch(offset, out)` fills `out` with the bytes from `offset`, or fails (with the
/// fault it reports, Fault::io for a network error). The app supplies it for remote packs (HTTP range requests); the
/// core calls it on its I/O thread only, never on the render thread.
class RangeSource final : public ByteSource {
public:
    using Fetch = std::function<Result<void>(std::uint64_t offset, std::span<std::byte> out)>;

    RangeSource(std::uint64_t size, Fetch fetch) noexcept : size_(size), fetch_(std::move(fetch)) {}

    [[nodiscard]] std::uint64_t size() const noexcept override { return size_; }
    [[nodiscard]] Result<void> read(std::uint64_t offset, std::span<std::byte> out) const override;

private:
    std::uint64_t size_;
    Fetch fetch_;
};

} // namespace atlas::tiles
