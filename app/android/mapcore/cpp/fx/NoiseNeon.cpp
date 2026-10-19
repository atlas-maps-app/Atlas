// WebKit's turbulence four pixels at a time (fx/Noise.hpp's fill_lanes, plan-vector T7, N17): clang's vector types,
// NEON on arm64 (vfmaq_f32 where the port fuses) and SSE on x86_64 (FMA3 where the target has it, else libm's fmaf
// per lane, correctly rounded too), every operation the scalar reference's in its order, so the bytes are the same.
//
// What a pixel's noise takes from its column (the lattice cell, its fraction and smooth step along x, at each octave)
// is computed once per column of the block, and what it takes from its row once per row; a pixel then reads its four
// corners' selector entries and gradients. The gradients by entry hold one entry's four channels in eight floats, so
// a corner's gradients for four pixels are eight loads and two 4 × 4 transposes.
#include "base/assert.hpp"
#include "base/opaque.hpp"
#include "base/simd.hpp"
#include "fx/Noise.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace atlas::fx {

namespace {

using simd::f32x4;
using simd::i32x4;

/// Java's (int) of a float, as the scalar reference's: truncated, NaN 0, saturated.
[[nodiscard]] std::int32_t java_int(float v) noexcept {
    if (v != v) return 0;
    if (v >= 2147483648.0F) return std::numeric_limits<std::int32_t>::max();
    if (v <= -2147483648.0F) return std::numeric_limits<std::int32_t>::min();
    return static_cast<std::int32_t>(v);
}

[[nodiscard]] std::int32_t next_cell(std::int32_t n) noexcept {
    return static_cast<std::int32_t>((static_cast<std::uint32_t>(n) + 1U) & static_cast<std::uint32_t>(lattice_mask));
}

/// Whether a and b lie on both sides of zero, lerp's first test: (a ≤ 0 and b ≥ 0) or (a ≥ 0 and b ≤ 0). Without
/// NaN that is "not both above zero and not both below", which the lesser and the greater of the two decide (however
/// their minimum orders two zeros: a zero is neither above nor below).
[[nodiscard]] i32x4 straddles(f32x4 a, f32x4 b) noexcept {
    return ~((__builtin_elementwise_min(a, b) > 0.0F) | (__builtin_elementwise_max(a, b) < 0.0F));
}

/// fx/Noise.cpp's lerp in each lane: every branch computed, the port's one selected.
[[nodiscard]] f32x4 lerp4(f32x4 a, f32x4 b, f32x4 t) noexcept {
    const i32x4 straddle = ((a <= 0.0F) & (b >= 0.0F)) | ((a >= 0.0F) & (b <= 0.0F));
    const f32x4 straight = simd::fma(t, b, (1.0F - t) * a);
    const f32x4 x = simd::fma(t, b - a, a);
    const i32x4 rising = ~((t > 1.0F) ^ (b > a)); // (t > 1) == (b > a)
    const f32x4 high = simd::select(opaque(b < x), x, b);
    const f32x4 low = simd::select(opaque(x < b), x, b);
    const f32x4 bent = simd::select(t == 1.0F, b, simd::select(rising, high, low));
    return simd::select(straddle, straight, bent);
}

/// The largest t for which lerp4_inside is lerp4: 1 − 2^-23.
constexpr float inside_bound = 0.99999988079071044921875F;

/// lerp4 for 0 ≤ t ≤ 1 − 2^-23, where it is libc++'s first two branches alone. There t is never 1, and the clamp that
/// keeps the result monotonic never acts: with d = b − a rounded (|d − (b − a)| ≤ 2^-24 |b − a|), t·d stays below
/// |b − a| in magnitude because t · (1 + 2^-24) < 1, so a + t·d lies strictly between a and b or is a when they are
/// equal, and fma(t, d, a), rounded to the nearest float, cannot pass b. Every screen field's t is a smooth step of a
/// fraction at most 1 − 2^-11 (its lattice position is at least 4096, where a float's step is 2^-11 or more), at most
/// 1 − 6·2^-23; rows and columns outside the bound take lerp4.
[[nodiscard]] f32x4 lerp4_inside(f32x4 a, f32x4 b, f32x4 t) noexcept {
    return simd::select(straddles(a, b), simd::fma(t, b, (1.0F - t) * a), simd::fma(t, b - a, a));
}

/// Whether every t of `values` lies where lerp4_inside is lerp4.
[[nodiscard]] bool inside(std::span<const float> values) noexcept {
    return std::ranges::all_of(values, [](float t) { return t >= 0.0F && t <= inside_bound; });
}

/// Four pixels' gradients at one corner: channel c's x and y for lanes 0 to 3, from the entries e of the lanes.
template <bool Upper>
void gather(const float* by_entry, const std::array<std::int32_t, 4>& e, std::array<f32x4, 4>& gx,
            std::array<f32x4, 4>& gy) noexcept {
    const auto a0 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[0]) * 8));
    const auto a1 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[1]) * 8));
    const auto a2 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[2]) * 8));
    const auto a3 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[3]) * 8));
    const f32x4 t0 = __builtin_shufflevector(a0, a1, 0, 4, 1, 5);
    const f32x4 t1 = __builtin_shufflevector(a2, a3, 0, 4, 1, 5);
    const f32x4 t2 = __builtin_shufflevector(a0, a1, 2, 6, 3, 7);
    const f32x4 t3 = __builtin_shufflevector(a2, a3, 2, 6, 3, 7);
    gx[0] = __builtin_shufflevector(t0, t1, 0, 1, 4, 5);
    gy[0] = __builtin_shufflevector(t0, t1, 2, 3, 6, 7);
    gx[1] = __builtin_shufflevector(t2, t3, 0, 1, 4, 5);
    gy[1] = __builtin_shufflevector(t2, t3, 2, 3, 6, 7);
    if constexpr (Upper) {
        const auto b0 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[0]) * 8) + 4);
        const auto b1 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[1]) * 8) + 4);
        const auto b2 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[2]) * 8) + 4);
        const auto b3 = simd::load<f32x4>(by_entry + (static_cast<std::ptrdiff_t>(e[3]) * 8) + 4);
        const f32x4 u0 = __builtin_shufflevector(b0, b1, 0, 4, 1, 5);
        const f32x4 u1 = __builtin_shufflevector(b2, b3, 0, 4, 1, 5);
        const f32x4 u2 = __builtin_shufflevector(b0, b1, 2, 6, 3, 7);
        const f32x4 u3 = __builtin_shufflevector(b2, b3, 2, 6, 3, 7);
        gx[2] = __builtin_shufflevector(u0, u1, 0, 1, 4, 5);
        gy[2] = __builtin_shufflevector(u0, u1, 2, 3, 6, 7);
        gx[3] = __builtin_shufflevector(u2, u3, 0, 1, 4, 5);
        gy[3] = __builtin_shufflevector(u2, u3, 2, 3, 6, 7);
    }
}

/// What the pixels of one column share at each octave: the lattice cell's selector entries along x, the fraction,
/// one less, and the smooth step (structure of arrays, the octaves one after the other, each `padded` long).
struct Columns {
    std::vector<float> fx;
    std::vector<float> fx1;
    std::vector<float> sx;
    std::vector<std::int32_t> i;
    std::vector<std::int32_t> j;
};

/// What the pixels of one row share at one octave.
struct RowTerms {
    float fy = 0;
    float fy1 = 0;
    float sy = 0;
    std::size_t by0 = 0;
    std::size_t by1 = 0;
};

/// The bytes of four lanes' sums for one channel.
[[nodiscard]] i32x4 bytes_of(f32x4 sum, bool fractal) noexcept {
    f32x4 s = sum;
    if (fractal) s = (s * 0.5F) + 0.5F;
    // Clamped into [−1, 256] first, so the conversion stays in range; the bytes are those of the scalar truncation.
    const f32x4 v = simd::select(opaque(s * 255.0F > 256.0F), f32x4(256.0F),
                                 simd::select(opaque(s * 255.0F < -1.0F), f32x4(-1.0F), s * 255.0F));
    const i32x4 n = simd::to_int(v);
    return n < 0 ? i32x4(0) : (n > 255 ? i32x4(255) : n);
}

/// The block's rows with the channels of `need` (Need: a fixed mask, or 0 for `need` as given); Inside: every column's
/// smooth step within lerp4_inside's bound (each row's is checked as it comes).
template <std::uint8_t Need, bool Inside>
void rows(const Lattice& lattice, const Turbulence& t, Grid grid, Block block, std::uint8_t need, const Columns& cols,
          std::size_t padded, std::span<Argb> out, std::size_t stride) {
    const std::uint8_t mask = Need != 0 ? Need : need;
    const bool upper = (mask & (channel_blue | channel_alpha)) != 0;
    const std::span<const std::int32_t> sel = lattice.selector();
    const float* by_entry = lattice.by_entry().data();
    const auto octaves = static_cast<std::size_t>(std::max(t.octaves, 0));
    const auto w = static_cast<std::size_t>(block.width);
    std::vector<RowTerms> terms(octaves);
    for (std::int32_t r = 0; r < block.height; ++r) {
        const float uy = static_cast<float>(block.top + r + 1) * grid.inv_y;
        float vy = uy * t.base_y;
        for (RowTerms& yt : terms) {
            const float py = vy + perlin_n;
            const std::int32_t iy = java_int(py);
            yt.fy = py - static_cast<float>(iy);
            yt.fy1 = yt.fy - 1.0F;
            yt.sy = smooth(yt.fy);
            yt.by0 = static_cast<std::size_t>(iy & lattice_mask);
            yt.by1 = static_cast<std::size_t>(next_cell(iy));
            vy *= 2.0F;
        }
        Argb* row = out.data() + (static_cast<std::size_t>(r) * stride);
        if constexpr (Inside) {
            if (!std::ranges::all_of(terms,
                                     [](const RowTerms& yt) { return yt.sy >= 0.0F && yt.sy <= inside_bound; })) {
                // a row outside the bound (only below the lattice's origin): the general lerp for it
                const Block one{.left = block.left, .top = block.top + r, .width = block.width, .height = 1};
                rows<0, false>(lattice, t, grid, one, mask, cols, padded, std::span<Argb>(row, w), stride);
                continue;
            }
        }
        for (std::size_t k = 0; k < padded; k += 4) {
            std::array<f32x4, 4> sum{};
            float inv_ratio = 1.0F;
            for (std::size_t o = 0; o < octaves; ++o) {
                const RowTerms& yt = terms[o];
                const std::size_t at = (o * padded) + k;
                const auto fx = simd::load<f32x4>(cols.fx.data() + at);
                const auto fx1 = simd::load<f32x4>(cols.fx1.data() + at);
                const auto sx = simd::load<f32x4>(cols.sx.data() + at);
                std::array<std::int32_t, 4> e00{};
                std::array<std::int32_t, 4> e10{};
                std::array<std::int32_t, 4> e01{};
                std::array<std::int32_t, 4> e11{};
                for (std::size_t l = 0; l < 4; ++l) {
                    const auto i = static_cast<std::size_t>(cols.i[at + l]);
                    const auto j = static_cast<std::size_t>(cols.j[at + l]);
                    e00[l] = sel[i + yt.by0];
                    e10[l] = sel[j + yt.by0];
                    e01[l] = sel[i + yt.by1];
                    e11[l] = sel[j + yt.by1];
                }
                std::array<f32x4, 4> gx00{};
                std::array<f32x4, 4> gy00{};
                std::array<f32x4, 4> gx10{};
                std::array<f32x4, 4> gy10{};
                std::array<f32x4, 4> gx01{};
                std::array<f32x4, 4> gy01{};
                std::array<f32x4, 4> gx11{};
                std::array<f32x4, 4> gy11{};
                if (Need != 0 ? ((Need & (channel_blue | channel_alpha)) != 0) : upper) {
                    gather<true>(by_entry, e00, gx00, gy00);
                    gather<true>(by_entry, e10, gx10, gy10);
                    gather<true>(by_entry, e01, gx01, gy01);
                    gather<true>(by_entry, e11, gx11, gy11);
                } else {
                    gather<false>(by_entry, e00, gx00, gy00);
                    gather<false>(by_entry, e10, gx10, gy10);
                    gather<false>(by_entry, e01, gx01, gy01);
                    gather<false>(by_entry, e11, gx11, gy11);
                }
                const f32x4 fy(yt.fy);
                const f32x4 fy1(yt.fy1);
                const f32x4 sy(yt.sy);
                for (std::size_t c = 0; c < 4; ++c) {
                    if ((mask & (1U << c)) == 0) continue;
                    f32x4 u = simd::fma(fx, gx00[c], fy * gy00[c]);
                    f32x4 v = simd::fma(fx1, gx10[c], fy * gy10[c]);
                    const f32x4 a = Inside ? lerp4_inside(u, v, sx) : lerp4(u, v, sx);
                    u = simd::fma(fx, gx01[c], fy1 * gy01[c]);
                    v = simd::fma(fx1, gx11[c], fy1 * gy11[c]);
                    const f32x4 b = Inside ? lerp4_inside(u, v, sx) : lerp4(u, v, sx);
                    const f32x4 n = Inside ? lerp4_inside(a, b, sy) : lerp4(a, b, sy);
                    sum[c] += (t.fractal ? n : simd::abs(n)) * inv_ratio;
                }
                inv_ratio *= 0.5F;
            }
            i32x4 argb = (mask & channel_alpha) != 0 ? bytes_of(sum[3], t.fractal) << 24 : i32x4(255 << 24);
            for (std::size_t c = 0; c < 3; ++c) {
                if ((mask & (1U << c)) != 0) {
                    argb |= bytes_of(sum[c], t.fractal) << static_cast<std::int32_t>(16 - (8 * c));
                }
            }
            if (k + 4 <= w) {
                simd::store(row + k, argb);
            } else {
                for (std::size_t l = 0; k + l < w; ++l) row[k + l] = static_cast<Argb>(argb[static_cast<int>(l)]);
            }
        }
    }
}

} // namespace

void fill_lanes(const Lattice& lattice, const Turbulence& t, Grid grid, Block block, std::uint8_t need,
                std::span<Argb> out, std::size_t stride) {
    if (block.width <= 0 || block.height <= 0) return;
    const auto w = static_cast<std::size_t>(block.width);
    ATLAS_ASSERT(stride >= w && out.size() >= ((static_cast<std::size_t>(block.height) - 1) * stride) + w,
                 "a field's block does not fit its output");
    const std::span<const std::int32_t> sel = lattice.selector();
    const std::size_t padded = (w + 3) & ~std::size_t{3};
    const auto octaves = static_cast<std::size_t>(std::max(t.octaves, 0));
    Columns cols;
    cols.fx.resize(octaves * padded);
    cols.fx1.resize(octaves * padded);
    cols.sx.resize(octaves * padded);
    cols.i.resize(octaves * padded);
    cols.j.resize(octaves * padded);
    for (std::size_t k = 0; k < padded; ++k) {
        const std::int32_t x = block.left + static_cast<std::int32_t>(k);
        float vx = static_cast<float>(x + 1) * grid.inv_x * t.base_x;
        for (std::size_t o = 0; o < octaves; ++o) {
            const std::size_t at = (o * padded) + k;
            const float px = vx + perlin_n;
            const std::int32_t ix = java_int(px);
            const float fx = px - static_cast<float>(ix);
            cols.fx[at] = fx;
            cols.fx1[at] = fx - 1.0F;
            cols.sx[at] = smooth(fx);
            cols.i[at] = sel[static_cast<std::size_t>(ix & lattice_mask)];
            cols.j[at] = sel[static_cast<std::size_t>(next_cell(ix))];
            vx *= 2.0F;
        }
    }
    if (!inside(cols.sx)) {
        rows<0, false>(lattice, t, grid, block, need, cols, padded, out, stride);
        return;
    }
    switch (need) {
    case channels_all: rows<channels_all, true>(lattice, t, grid, block, need, cols, padded, out, stride); break;
    case channel_red | channel_green:
        rows<channel_red | channel_green, true>(lattice, t, grid, block, need, cols, padded, out, stride);
        break;
    default: rows<0, true>(lattice, t, grid, block, need, cols, padded, out, stride); break;
    }
}

} // namespace atlas::fx
