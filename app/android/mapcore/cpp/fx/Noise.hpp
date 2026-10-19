// WebKit's turbulence on the CPU, exactly (plan-vector D10, N17, K17a): `feTurbulence` as the port computes it
// (MV/svg/Turbulence.kt: init, colorWebKit, fillWebKit), in single precision as WebKit's FETurbulenceSoftwareApplier
// does, every byte the port's. The port fuses four gradient products per channel per octave, `smooth` and libc++'s
// std::lerp with ExactMath.fma, "the way Apple's clang contracts them on arm64"; everything else is rounded on its own.
// Here too: the core compiles with contraction off, and the fused operations are std::fma (simd::fma in the lanes),
// correctly rounded on every target, as ExactMath.fma is. The octave's division by its ratio, a power of two, is a
// multiplication by the exact reciprocal: the same number.
//
// A field is the noise over a grid of filter pixels: pixel (x, y) is the noise at the user point
// ((x + 1) · inv_x, (y + 1) · inv_y), its far corner, as SVG filters sample it, where inv_x and inv_y are one over the
// filter scale as floats. It depends on its own coordinates alone, so any block of a field equals the same pixels of
// any other block that covers them, bit for bit (the port's TurbulenceCache keeps tiles on that ground; the core makes
// a screen's fields once per size, fx/Fields.hpp).
//
// Two paths compute the same bytes: `fill`, the scalar reference (the port's lines one by one), and `fill_lanes`
// (fx/NoiseNeon.cpp), four pixels at a time in clang's vector types, NEON on arm64, with what a row or a column shares
// computed once. The tests hold the lanes to the reference and both to the port (CP/tests/fx,
// MT/fx/FieldParityTest.kt).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace atlas::fx {

/// The spec's lattice size (BSize) and its mask, and the size as a count of entries.
inline constexpr std::int32_t lattice_size = 256;
inline constexpr std::int32_t lattice_mask = 255;
inline constexpr std::size_t lattice_entries = 256;

/// The spec's offset of the lattice position (PerlinN).
inline constexpr float perlin_n = 4096.0F;

/// A pixel's channels as bits of a mask: bit 0 red, 1 green, 2 blue, 3 alpha (the port's `need`).
inline constexpr std::uint8_t channel_red = 1;
inline constexpr std::uint8_t channel_green = 2;
inline constexpr std::uint8_t channel_blue = 4;
inline constexpr std::uint8_t channel_alpha = 8;
inline constexpr std::uint8_t channels_all = 15;

/// One `feTurbulence` primitive as WebKit reads it: single precision, the seed as written.
struct Turbulence {
    float seed = 0.0F;
    float base_x = 0.0F;
    float base_y = 0.0F;
    std::int32_t octaves = 1;
    bool fractal = true; ///< fractalNoise; false is the type turbulence (the absolute values summed)

    friend bool operator==(const Turbulence&, const Turbulence&) = default;
};

/// The spec's Park–Miller generator (Turbulence.random): the next number after `seed` (1 to 2^31 − 2).
[[nodiscard]] constexpr std::int64_t random(std::int64_t seed) noexcept {
    constexpr std::int64_t m = 2147483647;
    constexpr std::int64_t a = 16807;
    constexpr std::int64_t q = 127773;
    constexpr std::int64_t r = 2836;
    std::int64_t next = (a * (seed % q)) - (r * (seed / q));
    if (next <= 0) next += m;
    return next;
}

/// A seed's generator (the port's Turbulence init): the lattice selector and the gradients as WebKit keeps them,
/// single precision, each normalised by its length. Immutable once made; threads share it.
class Lattice {
public:
    /// The generator of `seed`: truncated towards zero, then clamped into 1 to 2^31 − 2, as the spec says.
    explicit Lattice(float seed) noexcept;

    /// The selector's 2B + 2 entries (each 0 to 255): the permutation, then its first B + 2 again.
    [[nodiscard]] std::span<const std::int32_t> selector() const noexcept { return selector_; }
    /// The gradients: channel c's entry i at [(c · 256 + i) · 2] (x) and [+ 1] (y), Turbulence.gradientsWebKit.
    [[nodiscard]] std::span<const float> gradients() const noexcept { return gradients_; }
    /// The gradients as the generator drew them, before normalising: the numerators gx · 256 and gy · 256 (−256 to
    /// 255), laid out as gradients(), Turbulence.rawGradients.
    [[nodiscard]] std::span<const std::int32_t> raw_gradients() const noexcept { return raw_; }
    /// The gradients by entry for the lanes: entry i's eight floats at [i · 8], channel c's x at [i · 8 + 2c] and y
    /// after it, so one entry's four channels are two loads.
    [[nodiscard]] std::span<const float> by_entry() const noexcept { return by_entry_; }

private:
    std::array<std::int32_t, (2 * lattice_entries) + 2> selector_{};
    std::array<float, 4 * lattice_entries * 2> gradients_{};
    std::array<std::int32_t, 4 * lattice_entries * 2> raw_{};
    std::array<float, lattice_entries * 8> by_entry_{};
};

/// A pixel as the port packs it: `a r g b` bytes, unpremultiplied; a channel outside the mask is 0, alpha 255.
using Argb = std::uint32_t;

/// The noise's four channels at the user point (x, y), Turbulence.colorWebKit: each channel's sum over the octaves,
/// `(sum + 1) / 2` for fractal noise, times 255 and truncated, clamped to 0 to 255; the channels in `need` only.
[[nodiscard]] Argb color(const Lattice& lattice, const Turbulence& t, float x, float y,
                         std::uint8_t need = channels_all) noexcept;

/// A block of filter pixels: `width` × `height` from (left, top).
struct Block {
    std::int32_t left = 0;
    std::int32_t top = 0;
    std::int32_t width = 0;
    std::int32_t height = 0;
};

/// The grid a field is sampled on: one over the filter scale, as floats (the port's 1f / scale).
struct Grid {
    float inv_x = 1.0F;
    float inv_y = 1.0F;
};

/// The grid of a filter scale: 1 / scale in float, as the port divides it.
[[nodiscard]] constexpr Grid grid_of(float scale_x, float scale_y) noexcept {
    return Grid{.inv_x = 1.0F / scale_x, .inv_y = 1.0F / scale_y};
}

/// Turbulence.fillWebKit, the scalar reference: the noise over `block` on `grid`, pixel (x, y) at the user point
/// ((x + 1) · inv_x, (y + 1) · inv_y); row r of the block goes to out from r · stride. `out` holds at least
/// (height − 1) · stride + width pixels.
void fill(const Lattice& lattice, const Turbulence& t, Grid grid, Block block, std::uint8_t need, std::span<Argb> out,
          std::size_t stride);

/// fill, four pixels at a time (fx/NoiseNeon.cpp): the same bytes.
void fill_lanes(const Lattice& lattice, const Turbulence& t, Grid grid, Block block, std::uint8_t need,
                std::span<Argb> out, std::size_t stride);

/// libc++'s std::lerp(a, b, t) for floats with its products fused as clang emits them (Turbulence.lerp).
[[nodiscard]] float lerp(float a, float b, float t) noexcept;

/// The spec's smooth step, t² · (3 − 2t), as the port fuses it: (t · t) · fma(−2, t, 3).
[[nodiscard]] float smooth(float t) noexcept;

} // namespace atlas::fx
