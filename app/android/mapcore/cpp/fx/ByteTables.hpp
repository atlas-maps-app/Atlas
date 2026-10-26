// The byte arithmetic of the renders' filters (plan-vector D7, D10, K17a): SvgFilterRaster.Bytes, which does it as
// WebKit, vImage and Core Graphics do on macOS, the browser the renders are checked in. Pixels are packed `a r g b`
// bytes, premultiplied or not as each function says, in linear RGB or sRGB.
//
// Two tables convert a premultiplied pixel's colour from one colour space to the other by its exact unpremultiplied
// value, as Core Graphics does: per alpha a and premultiplied channel c, the rounded byte of the transfer function at
// min(1, c / a), indexed a · 256 + c. The port computes them with Java's pow, which no C++ libm reproduces bit for bit,
// so they are not computed here: mapview's ByteTablesExportTest writes them from SvgFilterRaster's own functions into
// fx/tables/, and fails when the files there differ. The GPU reads the same bytes (set 1's byte_tables,
// fx/FieldCache.hpp).
#pragma once

#include "fx/Noise.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::fx::bytes {

/// Entries of one table: 256 alphas × 256 channel values.
inline constexpr std::size_t table_size = std::size_t{256} * 256;

/// The premultiplied-to-linear table (SvgFilterRaster's PREMUL_TO_LINEAR): entry a · 256 + c.
[[nodiscard]] std::span<const std::uint8_t, table_size> to_linear() noexcept;
/// The premultiplied-to-sRGB table (PREMUL_TO_SRGB), with Core Graphics' measured exceptions at alpha 255.
[[nodiscard]] std::span<const std::uint8_t, table_size> to_srgb() noexcept;

/// A pixel's channel: alpha (24), red (16), green (8), blue (0).
[[nodiscard]] constexpr std::uint32_t channel(Argb p, std::uint32_t shift) noexcept { return (p >> shift) & 0xFFU; }
/// A pixel of four bytes.
[[nodiscard]] constexpr Argb pack(std::uint32_t a, std::uint32_t r, std::uint32_t g, std::uint32_t b) noexcept {
    return (a << 24U) | (r << 16U) | (g << 8U) | b;
}

/// vImage's premultiply: (c · a + 127) / 255.
[[nodiscard]] constexpr Argb premultiply(Argb p) noexcept {
    const std::uint32_t a = p >> 24U;
    if (a == 255) return p;
    if (a == 0) return 0;
    auto c = [a](std::uint32_t v) { return ((v * a) + 127U) / 255U; };
    return pack(a, c(channel(p, 16)), c(channel(p, 8)), c(channel(p, 0)));
}

/// vImage's unpremultiply: (c · 255 + a / 2) / a, at most 255.
[[nodiscard]] constexpr Argb unpremultiply(Argb p) noexcept {
    const std::uint32_t a = p >> 24U;
    if (a == 255) return p;
    if (a == 0) return 0;
    auto u = [a](std::uint32_t v) {
        const std::uint32_t q = ((v * 255U) + (a / 2U)) / a;
        return q < 255U ? q : 255U;
    };
    return pack(a, u(channel(p, 16)), u(channel(p, 8)), u(channel(p, 0)));
}

/// A premultiplied pixel converted to linear RGB (`to_linear`) or to sRGB by its exact unpremultiplied colour,
/// returned unpremultiplied and rounded (the tables).
[[nodiscard]] Argb unpremultiply_exact(Argb p, bool to_linear) noexcept;

/// An unpremultiplied pixel's colour converted to linear RGB (`to_linear`) or to sRGB, rounded (the tables' row 255).
[[nodiscard]] Argb map_unpremultiplied(Argb p, bool to_linear) noexcept;

/// A premultiplied sRGB pixel as Core Graphics draws it into a linear-RGB buffer.
[[nodiscard]] Argb srgb_to_linear_premultiplied(Argb p) noexcept;

/// `Uint8ClampedArray`'s conversion: round half to even, clamped to 0 to 255 (NaN 0).
[[nodiscard]] std::uint32_t clamped(double v) noexcept;

/// Every channel of premultiplied `p` times f / 255, rounded.
[[nodiscard]] constexpr Argb scale(Argb p, std::uint32_t f) noexcept {
    if (f == 255) return p;
    if (f == 0) return 0;
    auto s = [f](std::uint32_t v) { return ((v * f) + 127U) / 255U; };
    return pack(s(channel(p, 24)), s(channel(p, 16)), s(channel(p, 8)), s(channel(p, 0)));
}

/// Channel by channel p + q, at most 255.
[[nodiscard]] constexpr Argb add(Argb p, Argb q) noexcept {
    auto c = [p, q](std::uint32_t shift) {
        const std::uint32_t v = channel(p, shift) + channel(q, shift);
        return v < 255U ? v : 255U;
    };
    return pack(c(24), c(16), c(8), c(0));
}

/// Premultiplied source-over.
[[nodiscard]] constexpr Argb over(Argb s, Argb d) noexcept {
    const std::uint32_t sa = s >> 24U;
    if (sa == 255 || d == 0) return s;
    if (sa == 0) return d;
    return add(s, scale(d, 255U - sa));
}

/// A colour matrix as SvgFilterRaster's MatrixNode applies it to one unpremultiplied pixel, the result
/// premultiplied: a matrix without offsets through vImage's integer multiply (each coefficient rounded to 1/256, the
/// sum rounded and clamped), any other as WebKit's float code computes each row (fused as the port fuses it), stored
/// like Uint8ClampedArray.
class ColourMatrix {
public:
    /// `m`: the 4 × 5 matrix by rows, as feColorMatrix's values (saturate and hueRotate expanded).
    explicit ColourMatrix(const std::array<float, 20>& m) noexcept;

    /// The matrix on unpremultiplied `p`, premultiplied.
    [[nodiscard]] Argb apply(Argb p) const noexcept;

    /// Whether vImage's integer path applies (no offsets).
    [[nodiscard]] bool integer() const noexcept { return integer_; }

private:
    [[nodiscard]] std::uint32_t row(std::size_t k, float r, float g, float b, float a) const noexcept;

    std::array<float, 20> m_;
    std::array<std::int32_t, 16> q_{}; ///< column-major: q[col · 4 + row], Math.round(m · 256)
    bool integer_ = false;
};

} // namespace atlas::fx::bytes

namespace atlas::fx {

/// Where each table begins in the byte tables' buffer, in bytes (shaders/fx/mat.slang's FX_TABLE_): the three 64 KB
/// byte tables, then mat's edge levels (fx/Mat.hpp's mat_edge_table) for 8-bit outputs and for 10-bit ones, 16 bits an
/// entry, little-endian, then the crowns' table (crown_table).
inline constexpr std::uint32_t table_to_linear = 0;
inline constexpr std::uint32_t table_to_srgb = 65536;
inline constexpr std::uint32_t table_mat = 131072;
inline constexpr std::uint32_t table_edge8 = 196608;
inline constexpr std::uint32_t table_edge10 = table_edge8 + (256 * 2);
inline constexpr std::uint32_t table_crown = table_edge10 + (1024 * 2);
/// The byte tables' size in bytes.
inline constexpr std::uint32_t tables_bytes = table_crown + bytes::table_size;

/// The crown filter's colour of an opaque pixel of a crown (render/closeup.py's crown_filter, SvgFilterRaster's steps):
/// entry c · 256 + red for an sRGB level c of the crown's colour and the crown tone's field byte `red` there (the
/// noise's red as the tone's colour matrix reads it). The colour in linear light (premultiplied, opaque), the tone the
/// matrix's (1 0 0 0 .5 on each colour row: red + 127.5, and opaque), their arithmetic product (k1 = 1:
/// float(c · t) · float(1 / 255), truncated), opaque (the `in` keeps it), written back as sRGB.
[[nodiscard]] std::span<const std::uint8_t, bytes::table_size> crown_table() noexcept;

/// The byte tables as the GPU reads them: to_linear, to_srgb, mat's opaque table, mat's edge levels, then the crowns'.
[[nodiscard]] std::vector<std::uint8_t> gpu_tables();

} // namespace atlas::fx
