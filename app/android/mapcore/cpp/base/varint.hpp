// Variable-length integers as the render tiles' columns store them (plan-vector Appendix A: zigzag-varint deltas):
// LEB128, seven bits a byte, low group first. The reader is strict: it checks every byte against the input's end and
// accepts only the one shortest encoding of each value, so a reader and a deterministic writer agree byte for byte.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>

namespace atlas {

/// Why bytes are not a varint.
enum class VarintError : unsigned char {
    truncated, ///< the input ended inside a varint
    overlong,  ///< a longer encoding than the value needs (a trailing zero group)
    overflow,  ///< more than 64 bits
};

/// The most bytes a 64-bit varint takes.
inline constexpr std::size_t varint_max_bytes = 10;

/// Maps signed to unsigned so that small magnitudes stay small: 0, -1, 1, -2 become 0, 1, 2, 3.
[[nodiscard]] constexpr std::uint64_t zigzag(std::int64_t value) noexcept {
    return (static_cast<std::uint64_t>(value) << 1U) ^ static_cast<std::uint64_t>(value >> 63);
}

/// The inverse of zigzag.
[[nodiscard]] constexpr std::int64_t unzigzag(std::uint64_t value) noexcept {
    return static_cast<std::int64_t>(value >> 1U) ^ -static_cast<std::int64_t>(value & 1U);
}

/// Writes the shortest encoding of a value into `out` (at least varint_max_bytes long) and returns the bytes used.
[[nodiscard]] constexpr std::size_t write_varint(std::uint64_t value,
                                                 std::span<std::byte, varint_max_bytes> out) noexcept {
    std::size_t n = 0;
    while (value >= 0x80U) {
        out[n++] = static_cast<std::byte>((value & 0x7FU) | 0x80U);
        value >>= 7U;
    }
    out[n++] = static_cast<std::byte>(value);
    return n;
}

/// Reads varints from bytes, front to back.
class VarintReader {
public:
    explicit constexpr VarintReader(std::span<const std::byte> bytes) noexcept : bytes_(bytes) {}

    /// The next unsigned varint.
    [[nodiscard]] constexpr std::expected<std::uint64_t, VarintError> next() noexcept {
        std::uint64_t value = 0;
        for (std::size_t i = 0; i < varint_max_bytes; ++i) {
            if (at_ >= bytes_.size()) return std::unexpected(VarintError::truncated);
            const auto byte = std::to_integer<std::uint64_t>(bytes_[at_++]);
            const std::uint64_t group = byte & 0x7FU;
            if (i == varint_max_bytes - 1 && group > 1U) return std::unexpected(VarintError::overflow);
            value |= group << (7U * i);
            if ((byte & 0x80U) == 0) {
                if (i > 0 && group == 0) return std::unexpected(VarintError::overlong);
                return value;
            }
        }
        return std::unexpected(VarintError::overflow);
    }

    /// The next zigzag-encoded signed varint.
    [[nodiscard]] constexpr std::expected<std::int64_t, VarintError> next_signed() noexcept {
        return next().transform(unzigzag);
    }

    /// Bytes consumed so far.
    [[nodiscard]] constexpr std::size_t position() const noexcept { return at_; }

    /// Whether every byte has been read.
    [[nodiscard]] constexpr bool done() const noexcept { return at_ == bytes_.size(); }

private:
    std::span<const std::byte> bytes_;
    std::size_t at_ = 0;
};

} // namespace atlas
