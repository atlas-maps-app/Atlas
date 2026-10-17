// Reading bytes from outside the core (plan-vector Appendix A): a cursor that checks every read against its end. The
// payload is little-endian with LEB128 varints (zig-zag for signed values); the container's header and directory are
// big-endian (D1's ByteSink). Varints are strict: at most ten bytes, the tenth at most 1, and only the shortest
// encoding of a value (the writers never write another), so a reader and a deterministic writer agree byte for byte.
//
// The first failure sticks: after it every read returns zero and the cursor stays at its end, so a decoding loop runs
// on without a check per value and asks ok() once, at the end of its block.
#pragma once

#include "tiles/Error.hpp"

#include <bit>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::tiles {

/// Whether `text` is well-formed UTF-8: no overlong forms, no surrogates, nothing past U+10FFFF.
[[nodiscard]] bool valid_utf8(std::span<const std::byte> text) noexcept;

/// The largest count a varint count may hold: Kotlin's Int, which the writers' counts and indexes are.
inline constexpr std::uint64_t max_count = 0x7FFF'FFFFU;

/// A bounds-checked cursor over bytes.
class Reader {
public:
    constexpr Reader() noexcept = default;
    constexpr explicit Reader(std::span<const std::byte> bytes) noexcept
        : begin_(bytes.data()), at_(bytes.data()), end_(bytes.data() + bytes.size()) {}

    /// Whether every read so far succeeded.
    [[nodiscard]] constexpr bool ok() const noexcept { return !failed_; }

    /// The first failure (meaningful when !ok()).
    [[nodiscard]] constexpr Fault fault() const noexcept { return fault_; }

    /// Bytes not read yet.
    [[nodiscard]] constexpr std::size_t remaining() const noexcept { return static_cast<std::size_t>(end_ - at_); }

    /// Bytes read so far.
    [[nodiscard]] constexpr std::size_t position() const noexcept { return static_cast<std::size_t>(at_ - begin_); }

    /// Whether every byte has been read (and nothing failed).
    [[nodiscard]] constexpr bool done() const noexcept { return ok() && at_ == end_; }

    /// Records a failure found by the caller (a value out of its field's range); the first one sticks.
    constexpr void fail(Fault fault) noexcept {
        if (!failed_) {
            failed_ = true;
            fault_ = fault;
        }
        at_ = end_;
    }

    [[nodiscard]] constexpr std::uint8_t u8() noexcept {
        if (at_ == end_) {
            fail(Fault::truncated);
            return 0;
        }
        return std::to_integer<std::uint8_t>(*at_++);
    }

    [[nodiscard]] constexpr std::uint16_t u16le() noexcept { return static_cast<std::uint16_t>(le(2)); }
    [[nodiscard]] constexpr std::uint32_t u32le() noexcept { return static_cast<std::uint32_t>(le(4)); }
    [[nodiscard]] constexpr std::uint64_t u64le() noexcept { return le(8); }
    [[nodiscard]] constexpr double f64le() noexcept { return std::bit_cast<double>(le(8)); }
    [[nodiscard]] constexpr std::uint32_t u32be() noexcept { return static_cast<std::uint32_t>(be(4)); }
    [[nodiscard]] constexpr std::uint64_t u64be() noexcept { return be(8); }

    /// An unsigned LEB128 varint.
    [[nodiscard]] constexpr std::uint64_t varu() noexcept {
        if (at_ != end_ && std::to_integer<unsigned>(*at_) < 0x80U) return std::to_integer<std::uint64_t>(*at_++);
        std::uint64_t value = 0;
        const std::byte* p = at_;
        for (unsigned i = 0; i < 10; ++i) {
            if (p == end_) {
                fail(Fault::truncated);
                return 0;
            }
            const auto b = std::to_integer<std::uint64_t>(*p++);
            if (i == 9 && b > 1U) { // more than 64 bits (a tenth byte of 2 or more, or an eleventh byte)
                fail(Fault::overflow);
                return 0;
            }
            value |= (b & 0x7FU) << (7U * i);
            if ((b & 0x80U) == 0) {
                if (b == 0 && i > 0) { // a trailing zero group: not the shortest encoding
                    fail(Fault::overlong);
                    return 0;
                }
                at_ = p;
                return value;
            }
        }
        fail(Fault::overflow); // not reached: the tenth byte either ends the varint or fails above
        return 0;
    }

    /// A zig-zag coded signed varint.
    [[nodiscard]] constexpr std::int64_t varz() noexcept { return unzigzag(varu()); }

    /// A varint count or index: at most max_count (Kotlin's varCount).
    [[nodiscard]] constexpr std::uint32_t count() noexcept {
        const std::uint64_t v = varu();
        if (v > max_count) {
            fail(Fault::overflow);
            return 0;
        }
        return static_cast<std::uint32_t>(v);
    }

    /// The next `n` bytes.
    [[nodiscard]] constexpr std::span<const std::byte> bytes(std::size_t n) noexcept {
        if (n > remaining()) {
            fail(Fault::truncated);
            return {};
        }
        const std::span<const std::byte> out(at_, n);
        at_ += n;
        return out;
    }

    /// Skips `n` bytes.
    constexpr void skip(std::size_t n) noexcept { (void)bytes(n); }

    /// The next varint-length block, as a reader of its own.
    [[nodiscard]] constexpr Reader block() noexcept {
        const std::uint32_t n = count();
        return Reader(bytes(n));
    }

    /// A varint-length UTF-8 string, checked.
    [[nodiscard]] std::string_view utf8() noexcept {
        const std::span<const std::byte> text = bytes(count());
        if (!valid_utf8(text)) {
            fail(Fault::bad_utf8);
            return {};
        }
        return {reinterpret_cast<const char*>(text.data()), text.size()}; // NOLINT: bytes as chars
    }

    /// The zig-zag decoding: 0, 1, 2, 3 to 0, −1, 1, −2.
    [[nodiscard]] static constexpr std::int64_t unzigzag(std::uint64_t z) noexcept {
        return static_cast<std::int64_t>(z >> 1U) ^ -static_cast<std::int64_t>(z & 1U);
    }

private:
    [[nodiscard]] constexpr std::uint64_t le(unsigned n) noexcept {
        if (remaining() < n) {
            fail(Fault::truncated);
            return 0;
        }
        std::uint64_t v = 0;
        for (unsigned k = 0; k < n; ++k) v |= std::to_integer<std::uint64_t>(at_[k]) << (8U * k);
        at_ += n;
        return v;
    }

    [[nodiscard]] constexpr std::uint64_t be(unsigned n) noexcept {
        if (remaining() < n) {
            fail(Fault::truncated);
            return 0;
        }
        std::uint64_t v = 0;
        for (unsigned k = 0; k < n; ++k) v = (v << 8U) | std::to_integer<std::uint64_t>(at_[k]);
        at_ += n;
        return v;
    }

    const std::byte* begin_ = nullptr;
    const std::byte* at_ = nullptr;
    const std::byte* end_ = nullptr;
    bool failed_ = false;
    Fault fault_ = Fault::truncated;
};

/// Wrapping 64-bit addition, as Kotlin's Long arithmetic wraps (signed overflow is undefined in C++).
[[nodiscard]] constexpr std::int64_t wrap_add(std::int64_t a, std::int64_t b) noexcept {
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) + static_cast<std::uint64_t>(b));
}

/// Wrapping 64-bit subtraction.
[[nodiscard]] constexpr std::int64_t wrap_sub(std::int64_t a, std::int64_t b) noexcept {
    return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) - static_cast<std::uint64_t>(b));
}

/// Bytes as chars, for text that was checked as UTF-8.
[[nodiscard]] inline std::string_view as_text(std::span<const std::byte> bytes) noexcept {
    return {reinterpret_cast<const char*>(bytes.data()), bytes.size()}; // NOLINT: bytes as chars
}

/// Chars as bytes.
[[nodiscard]] inline std::span<const std::byte> as_bytes(std::string_view text) noexcept {
    return {reinterpret_cast<const std::byte*>(text.data()), text.size()}; // NOLINT: chars as bytes
}

} // namespace atlas::tiles
