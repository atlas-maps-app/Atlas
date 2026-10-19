// WebKit's turbulence (fx/Noise.hpp): the seed's generator and the scalar reference, the port's lines one by one
// (MV/svg/Turbulence.kt).
#include "fx/Noise.hpp"

#include "base/assert.hpp"
#include "base/opaque.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::fx {

namespace {

/// Java's (long) of a float (Kotlin's Float.toLong): truncated towards zero, NaN 0, saturated at Long's limits.
[[nodiscard]] std::int64_t java_long(float v) noexcept {
    if (v != v) return 0;
    if (v >= 9.223372036854775808e18F) return std::numeric_limits<std::int64_t>::max();
    if (v <= -9.223372036854775808e18F) return std::numeric_limits<std::int64_t>::min();
    return static_cast<std::int64_t>(v);
}

/// Java's (int) of a float (Kotlin's Float.toInt): truncated towards zero, NaN 0, saturated at Int's limits.
[[nodiscard]] std::int32_t java_int(float v) noexcept {
    if (v != v) return 0;
    if (v >= 2147483648.0F) return std::numeric_limits<std::int32_t>::max();
    if (v <= -2147483648.0F) return std::numeric_limits<std::int32_t>::min();
    return static_cast<std::int32_t>(v);
}

/// (n + 1) & mask without the overflow C++ leaves undefined (Kotlin's Int wraps).
[[nodiscard]] std::int32_t next_cell(std::int32_t n) noexcept {
    return static_cast<std::int32_t>((static_cast<std::uint32_t>(n) + 1U) & static_cast<std::uint32_t>(lattice_mask));
}

/// One channel's byte from its sum: `(sum + 1) / 2` for fractal noise, times 255, truncated, clamped to 0 to 255.
[[nodiscard]] std::uint32_t byte_of(float sum, bool fractal) noexcept {
    float s = sum;
    if (fractal) s = (s * 0.5F) + 0.5F;
    return static_cast<std::uint32_t>(std::clamp(java_int(s * 255.0F), 0, 255));
}

} // namespace

float smooth(float t) noexcept { return (t * t) * std::fma(-2.0F, t, 3.0F); }

float lerp(float a, float b, float t) noexcept {
    if ((a <= 0.0F && b >= 0.0F) || (a >= 0.0F && b <= 0.0F)) return std::fma(t, b, (1.0F - t) * a);
    if (t == 1.0F) return b;
    const float x = std::fma(t, b - a, a);
    // Python-ordered selects, opaque so clang keeps them (fmaxnm and fminnm order the zeros their own way).
    if ((t > 1.0F) == (b > a)) return opaque(b < x) ? x : b;
    return opaque(x < b) ? x : b;
}

Lattice::Lattice(float seed) noexcept {
    constexpr std::int64_t m = 2147483647;
    constexpr std::int64_t b = lattice_size;
    std::int64_t s = java_long(seed);
    if (s <= 0) s = -(s % (m - 1)) + 1;
    s = std::min(s, m - 1);
    for (std::int32_t k = 0; k < 4; ++k) {
        for (std::int32_t i = 0; i < lattice_size; ++i) {
            selector_[static_cast<std::size_t>(i)] = i;
            double gx = 0;
            double gy = 0;
            std::int64_t nx = 0;
            std::int64_t ny = 0;
            do {
                s = random(s);
                nx = (s % (b + b)) - b;
                gx = static_cast<double>(nx) / static_cast<double>(b);
                s = random(s);
                ny = (s % (b + b)) - b;
                gy = static_cast<double>(ny) / static_cast<double>(b);
            } while (gx == 0.0 && gy == 0.0);
            const std::size_t at = ((static_cast<std::size_t>(k) * lattice_entries) + static_cast<std::size_t>(i)) * 2;
            raw_[at] = static_cast<std::int32_t>(nx);
            raw_[at + 1] = static_cast<std::int32_t>(ny);
            const auto fx = static_cast<float>(gx);
            const auto fy = static_cast<float>(gy);
            // Math.hypot of two multiples of 1/256 below 1 in magnitude: their squares and the sum are exact, so the
            // correctly rounded square root is the hypotenuse's double, and its float is Math.hypot's float for every
            // pair (held for all 512 × 512 by mapview's ByteTablesExportTest).
            const auto dx = static_cast<double>(fx);
            const auto dy = static_cast<double>(fy);
            const auto fn = static_cast<float>(std::sqrt((dx * dx) + (dy * dy)));
            gradients_[at] = fx / fn;
            gradients_[at + 1] = fy / fn;
        }
    }
    std::int32_t i = lattice_size;
    while (--i > 0) {
        const std::int32_t k = selector_[static_cast<std::size_t>(i)];
        s = random(s);
        const auto j = static_cast<std::size_t>(s % b);
        selector_[static_cast<std::size_t>(i)] = selector_[j];
        selector_[j] = k;
    }
    for (std::size_t n = 0; n < lattice_entries + 2; ++n) selector_[lattice_entries + n] = selector_[n];
    for (std::size_t e = 0; e < lattice_entries; ++e) {
        for (std::size_t c = 0; c < 4; ++c) {
            const std::size_t from = ((c * lattice_entries) + e) * 2;
            by_entry_[(e * 8) + (2 * c)] = gradients_[from];
            by_entry_[(e * 8) + (2 * c) + 1] = gradients_[from + 1];
        }
    }
}

Argb color(const Lattice& lattice, const Turbulence& t, float x, float y, std::uint8_t need) noexcept {
    const std::span<const std::int32_t> sel = lattice.selector();
    const std::span<const float> g = lattice.gradients();
    float vx = x * t.base_x;
    float vy = y * t.base_y;
    std::array<float, 4> sums{};
    float inv_ratio = 1.0F; // 1 / ratio: the octave's division as a multiplication, exactly
    for (std::int32_t o = 0; o < t.octaves; ++o) {
        const float px = vx + perlin_n;
        const std::int32_t ix = java_int(px);
        const float fx = px - static_cast<float>(ix);
        const float py = vy + perlin_n;
        const std::int32_t iy = java_int(py);
        const float fy = py - static_cast<float>(iy);
        const auto i = static_cast<std::size_t>(sel[static_cast<std::size_t>(ix & lattice_mask)]);
        const auto j = static_cast<std::size_t>(sel[static_cast<std::size_t>(next_cell(ix))]);
        const auto by0 = static_cast<std::size_t>(iy & lattice_mask);
        const auto by1 = static_cast<std::size_t>(next_cell(iy));
        const auto b00 = static_cast<std::size_t>(sel[i + by0]);
        const auto b10 = static_cast<std::size_t>(sel[j + by0]);
        const auto b01 = static_cast<std::size_t>(sel[i + by1]);
        const auto b11 = static_cast<std::size_t>(sel[j + by1]);
        const float sx = smooth(fx);
        const float sy = smooth(fy);
        const float fx1 = fx - 1.0F;
        const float fy1 = fy - 1.0F;
        for (std::size_t c = 0; c < 4; ++c) {
            if ((need & (1U << c)) == 0) continue;
            const std::size_t base = c * lattice_entries;
            std::size_t q = (base + b00) * 2;
            float u = std::fma(fx, g[q], fy * g[q + 1]);
            q = (base + b10) * 2;
            float v = std::fma(fx1, g[q], fy * g[q + 1]);
            const float a = lerp(u, v, sx);
            q = (base + b01) * 2;
            u = std::fma(fx, g[q], fy1 * g[q + 1]);
            q = (base + b11) * 2;
            v = std::fma(fx1, g[q], fy1 * g[q + 1]);
            const float b = lerp(u, v, sx);
            const float n = lerp(a, b, sy);
            sums[c] += (t.fractal ? n : std::fabs(n)) * inv_ratio;
        }
        vx *= 2.0F;
        vy *= 2.0F;
        inv_ratio *= 0.5F;
    }
    Argb out = 0;
    for (std::uint32_t c = 0; c < 4; ++c) {
        const std::uint32_t byte = (need & (1U << c)) == 0 ? (c == 3 ? 255U : 0U) : byte_of(sums[c], t.fractal);
        out |= byte << (c == 3 ? 24U : 16U - (8U * c));
    }
    return out;
}

void fill(const Lattice& lattice, const Turbulence& t, Grid grid, Block block, std::uint8_t need, std::span<Argb> out,
          std::size_t stride) {
    if (block.width <= 0 || block.height <= 0) return;
    const auto w = static_cast<std::size_t>(block.width);
    ATLAS_ASSERT(stride >= w && out.size() >= ((static_cast<std::size_t>(block.height) - 1) * stride) + w,
                 "a field's block does not fit its output");
    for (std::int32_t r = 0; r < block.height; ++r) {
        const float uy = static_cast<float>(block.top + r + 1) * grid.inv_y;
        std::size_t i = static_cast<std::size_t>(r) * stride;
        for (std::int32_t x = block.left; x < block.left + block.width; ++x) {
            out[i++] = color(lattice, t, static_cast<float>(x + 1) * grid.inv_x, uy, need);
        }
    }
}

} // namespace atlas::fx
