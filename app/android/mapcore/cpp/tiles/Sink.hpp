// Writing bytes the core's readers read back (plan-vector Appendix A; rendertiles' LeSink and render.data's ByteSink):
// the payload is little-endian with LEB128 varints in their shortest form (zig-zag for signed values), the container's
// header and directory big-endian. tiles/Bytes.hpp's Reader is its other half.
#pragma once

#include <bit>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::tiles {

/// A growable byte buffer.
class Sink {
public:
    void u8(std::uint64_t v) { bytes_.push_back(static_cast<std::byte>(v)); }
    void u16(std::uint64_t v) { le(v, 2); }
    void u32(std::uint64_t v) { le(v, 4); }
    void u64(std::uint64_t v) { le(v, 8); }
    void i32(std::int32_t v) { u32(static_cast<std::uint32_t>(v)); }
    void f64(double v) { u64(std::bit_cast<std::uint64_t>(v)); }

    void u32be(std::uint64_t v) { be(v, 4); }
    void u64be(std::uint64_t v) { be(v, 8); }

    /// An unsigned LEB128 varint, shortest form (a value of 2^63 or more takes ten bytes).
    void varu(std::uint64_t v) {
        while (v >= 0x80U) {
            u8((v & 0x7FU) | 0x80U);
            v >>= 7U;
        }
        u8(v);
    }

    /// A signed varint, zig-zag coded.
    void varz(std::int64_t v) { varu(zigzag(v)); }

    /// Bytes as they are.
    void raw(std::span<const std::byte> b) { bytes_.insert(bytes_.end(), b.begin(), b.end()); }

    /// A varint byte length and the string's bytes.
    void utf8(std::string_view s) {
        varu(s.size());
        raw(std::as_bytes(std::span(s.data(), s.size())));
    }

    /// Another sink's bytes after their varint length.
    void block(const Sink& b) {
        varu(b.size());
        raw(b.bytes());
    }

    /// Overwrites the four bytes at `at` (a header field filled in once its value is known).
    void put_u32(std::size_t at, std::uint64_t v) noexcept {
        for (std::size_t k = 0; k < 4; ++k) bytes_[at + k] = static_cast<std::byte>(v >> (8U * k));
    }

    [[nodiscard]] std::size_t size() const noexcept { return bytes_.size(); }
    [[nodiscard]] std::span<const std::byte> bytes() const noexcept { return bytes_; }
    void clear() noexcept { bytes_.clear(); }
    void reserve(std::size_t n) { bytes_.reserve(n); }
    /// The bytes, the sink left empty.
    [[nodiscard]] std::vector<std::byte> take() noexcept { return std::move(bytes_); }

    /// The zig-zag code of a signed value (Kotlin's `(v shl 1) xor (v shr 63)`).
    [[nodiscard]] static constexpr std::uint64_t zigzag(std::int64_t v) noexcept {
        return (static_cast<std::uint64_t>(v) << 1U) ^ static_cast<std::uint64_t>(v >> 63);
    }

private:
    void le(std::uint64_t v, unsigned n) {
        for (unsigned k = 0; k < n; ++k) u8(v >> (8U * k));
    }
    void be(std::uint64_t v, unsigned n) {
        for (unsigned k = n; k > 0; --k) u8(v >> (8U * (k - 1)));
    }

    std::vector<std::byte> bytes_;
};

} // namespace atlas::tiles
