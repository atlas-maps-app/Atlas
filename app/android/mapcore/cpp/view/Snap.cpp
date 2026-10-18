// Snapping at rest (view/Snap.hpp).
#include "view/Snap.hpp"

#include "frame/FrameBlock.hpp"

#include <array>
#include <bit>

namespace atlas::view {

double decimals(double v, int n) noexcept {
    static constexpr std::array<std::uint64_t, 4> powers{1, 10, 100, 1000};
    const auto bits = std::bit_cast<std::uint64_t>(v);
    const std::uint64_t sign = bits & (std::uint64_t{1} << 63U);
    const std::uint64_t magnitude = bits & ~sign;
    int e = static_cast<int>(magnitude >> 52U) - 1023;
    if (e >= 52 || n < 0 || n > 3) return v; // a whole double, an infinity or a NaN (or no such precision)
    std::uint64_t m = (magnitude & ((std::uint64_t{1} << 52U) - 1)) | (std::uint64_t{1} << 52U);
    if (e == -1023) { // subnormal: no hidden bit
        m = magnitude & ((std::uint64_t{1} << 52U) - 1);
        e = -1022;
    }
    const std::uint64_t p = m * powers[static_cast<std::size_t>(n)]; // 10^n·|v| = p · 2^(e − 52), p < 2^63
    const int shift = 52 - e;                                        // at least 1
    std::uint64_t q = 0;
    if (shift < 64) {
        q = p >> static_cast<unsigned>(shift);
        const std::uint64_t rest = p & ((std::uint64_t{1} << static_cast<unsigned>(shift)) - 1);
        const std::uint64_t half = std::uint64_t{1} << static_cast<unsigned>(shift - 1);
        if (rest > half || (rest == half && (q & 1U) != 0)) ++q;
    }
    const double r = static_cast<double>(q) / static_cast<double>(powers[static_cast<std::size_t>(n)]);
    return std::bit_cast<double>(std::bit_cast<std::uint64_t>(r) | sign);
}

float snap_tenth(float v) noexcept { return shaders::snap_tenth(v); }

} // namespace atlas::view
