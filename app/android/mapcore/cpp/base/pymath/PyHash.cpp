// CPython's hash() as the port computes it (R/py/PyHash.kt): Python/pyhash.c's SipHash and float hashing, and
// Objects/tupleobject.c's tuple hash, on 64-bit builds with PYTHONHASHSEED=0.
#include "base/pymath/PyHash.hpp"

#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::pymath {

namespace {

constexpr int bits = 61;
constexpr std::uint64_t modulus = (std::uint64_t{1} << bits) - 1U;
constexpr std::int64_t infinity_hash = 314159;

/// A signed hash as Python reports it: -1 is reserved for errors, so it becomes -2.
[[nodiscard]] constexpr std::int64_t reported(std::int64_t x) noexcept { return x == -1 ? -2 : x; }

/// SipHash-c-d over a stream of bytes, with c compression rounds (2 or 1) and d = 4 or 3 finalisation rounds, as
/// CPython's pyhash.c computes it with the all-zero key; nothing is buffered beyond one 8-byte word.
class SipHash {
public:
    explicit SipHash(int rounds) noexcept : rounds_(rounds) {}

    void push(std::uint8_t byte) noexcept {
        word_ |= static_cast<std::uint64_t>(byte) << (8U * fill_);
        ++length_;
        if (++fill_ == 8) {
            v3_ ^= word_;
            for (int r = 0; r < rounds_; ++r) {
                round();
            }
            v0_ ^= word_;
            word_ = 0;
            fill_ = 0;
        }
    }

    [[nodiscard]] std::uint64_t finish() noexcept {
        const std::uint64_t b = (static_cast<std::uint64_t>(length_) << 56U) | word_;
        v3_ ^= b;
        for (int r = 0; r < rounds_; ++r) {
            round();
        }
        v0_ ^= b;
        v2_ ^= 0xffU;
        for (int r = 0; r < (rounds_ == 2 ? 4 : 3); ++r) {
            round();
        }
        return (v0_ ^ v1_) ^ (v2_ ^ v3_);
    }

private:
    void round() noexcept {
        v0_ += v1_;
        v2_ += v3_;
        v1_ = std::rotl(v1_, 13) ^ v0_;
        v3_ = std::rotl(v3_, 16) ^ v2_;
        v0_ = std::rotl(v0_, 32);
        v2_ += v1_;
        v0_ += v3_;
        v1_ = std::rotl(v1_, 17) ^ v2_;
        v3_ = std::rotl(v3_, 21) ^ v0_;
        v2_ = std::rotl(v2_, 32);
    }

    // The key (k0, k1) is (0, 0): PYTHONHASHSEED=0.
    std::uint64_t v0_ = 0x736f6d6570736575U;
    std::uint64_t v1_ = 0x646f72616e646f6dU;
    std::uint64_t v2_ = 0x6c7967656e657261U;
    std::uint64_t v3_ = 0x7465646279746573U;
    std::uint64_t word_ = 0;
    unsigned fill_ = 0;
    std::size_t length_ = 0;
    int rounds_;
};

/// The next code point of `text` from `at` (UTF-8, surrogates allowed since a Python str may hold them), or -1 when
/// the text is not UTF-8 there.
[[nodiscard]] std::int32_t next_code_point(std::string_view text, std::size_t& at) noexcept {
    const auto byte = [&](std::size_t i) { return static_cast<std::uint32_t>(static_cast<unsigned char>(text[i])); };
    const std::uint32_t b0 = byte(at);
    std::size_t need = 0;
    std::uint32_t cp = 0;
    std::uint32_t min = 0;
    if (b0 < 0x80U) {
        ++at;
        return static_cast<std::int32_t>(b0);
    }
    if ((b0 & 0xE0U) == 0xC0U) {
        need = 1;
        cp = b0 & 0x1FU;
        min = 0x80U;
    } else if ((b0 & 0xF0U) == 0xE0U) {
        need = 2;
        cp = b0 & 0x0FU;
        min = 0x800U;
    } else if ((b0 & 0xF8U) == 0xF0U) {
        need = 3;
        cp = b0 & 0x07U;
        min = 0x10000U;
    } else {
        return -1; // a continuation byte, or a lead byte UTF-8 does not have
    }
    if (text.size() - at <= need) {
        return -1; // truncated
    }
    for (std::size_t i = 1; i <= need; ++i) {
        const std::uint32_t b = byte(at + i);
        if ((b & 0xC0U) != 0x80U) {
            return -1;
        }
        cp = (cp << 6U) | (b & 0x3FU);
    }
    if (cp < min || cp > 0x10FFFFU) {
        return -1; // overlong, or beyond Unicode
    }
    at += need + 1;
    return static_cast<std::int32_t>(cp);
}

} // namespace

std::int64_t hash_int(std::int64_t n) noexcept {
    const std::uint64_t magnitude = n < 0 ? 0U - static_cast<std::uint64_t>(n) : static_cast<std::uint64_t>(n);
    const auto x = static_cast<std::int64_t>(magnitude % modulus);
    return reported(n < 0 ? -x : x);
}

std::int64_t hash_unsigned(std::uint64_t n) noexcept { return static_cast<std::int64_t>(n % modulus); }

Result<std::int64_t> hash_float(double x) noexcept {
    if (!std::isfinite(x)) {
        if (std::isinf(x)) {
            return x > 0 ? infinity_hash : -infinity_hash;
        }
        return std::unexpected(Error::not_hashable);
    }
    int ex = frexp_exp(x);
    double m = x == 0.0 ? 0.0 : ldexp(x, -ex);
    std::int64_t sign = 1;
    if (m < 0) {
        sign = -1;
        m = -m;
    }
    std::uint64_t acc = 0;
    while (m != 0.0) {
        acc = ((acc << 28U) & modulus) | (acc >> static_cast<unsigned>(bits - 28));
        m *= 268435456.0; // 2^28
        ex -= 28;
        const auto y = static_cast<std::int64_t>(m);
        m -= static_cast<double>(y);
        acc += static_cast<std::uint64_t>(y);
        if (acc >= modulus) {
            acc -= modulus;
        }
    }
    ex = ex >= 0 ? ex % bits : bits - 1 - ((-1 - ex) % bits);
    acc = ((acc << static_cast<unsigned>(ex)) & modulus) | (acc >> static_cast<unsigned>(bits - ex));
    return reported(static_cast<std::int64_t>(acc) * sign);
}

std::int64_t hash_bytes(std::span<const std::byte> bytes, Compat compat) noexcept {
    if (bytes.empty()) {
        return 0;
    }
    SipHash sip(compat.sip_rounds());
    for (const std::byte b : bytes) {
        sip.push(static_cast<std::uint8_t>(b));
    }
    return reported(static_cast<std::int64_t>(sip.finish()));
}

Result<std::int64_t> hash_str(std::string_view utf8, Compat compat) noexcept {
    if (utf8.empty()) {
        return 0;
    }
    // CPython stores a str in one, two or four bytes per code point, by its largest, and hashes those bytes: one pass
    // finds the largest, the second streams them into SipHash.
    std::int32_t max_cp = 0;
    for (std::size_t at = 0; at < utf8.size();) {
        const std::int32_t cp = next_code_point(utf8, at);
        if (cp < 0) {
            return std::unexpected(Error::invalid_text);
        }
        max_cp = cp > max_cp ? cp : max_cp;
    }
    const unsigned kind = max_cp < 256 ? 1U : (max_cp < 65536 ? 2U : 4U);
    SipHash sip(compat.sip_rounds());
    for (std::size_t at = 0; at < utf8.size();) {
        const auto cp = static_cast<std::uint32_t>(next_code_point(utf8, at));
        for (unsigned b = 0; b < kind; ++b) {
            sip.push(static_cast<std::uint8_t>(cp >> (8U * b)));
        }
    }
    return reported(static_cast<std::int64_t>(sip.finish()));
}

} // namespace atlas::pymath
