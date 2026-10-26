// The filters' byte arithmetic (fx/ByteTables.hpp), and the two tables as mapview's ByteTablesExportTest wrote them:
// fx/tables/to_linear.inc and to_srgb.inc, a row of 256 bytes a line, each a string of hexadecimal digits.
#include "fx/ByteTables.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "fx/Mat.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <string_view>

namespace atlas::fx::bytes {

namespace {

/// A table's rows as the file holds them: 256 strings of 512 hexadecimal digits.
using HexRows = std::array<std::string_view, 256>;

constexpr HexRows linear_rows{
#include "fx/tables/to_linear.inc"
};

constexpr HexRows srgb_rows{
#include "fx/tables/to_srgb.inc"
};

[[nodiscard]] consteval bool whole(const HexRows& rows) {
    return std::ranges::all_of(rows, [](std::string_view r) { return r.size() == 512; });
}
static_assert(whole(linear_rows), "fx/tables/to_linear.inc holds 256 rows of 256 bytes in hexadecimal");
static_assert(whole(srgb_rows), "fx/tables/to_srgb.inc holds 256 rows of 256 bytes in hexadecimal");

[[nodiscard]] constexpr std::uint8_t nibble(char c) noexcept {
    if (c >= '0' && c <= '9') return static_cast<std::uint8_t>(c - '0');
    if (c >= 'a' && c <= 'f') return static_cast<std::uint8_t>(c - 'a' + 10);
    if (c >= 'A' && c <= 'F') return static_cast<std::uint8_t>(c - 'A' + 10);
    ATLAS_UNREACHABLE("a byte table holds a character that is not hexadecimal");
}

[[nodiscard]] std::array<std::uint8_t, table_size> decode(const HexRows& rows) noexcept {
    std::array<std::uint8_t, table_size> t{};
    for (std::size_t a = 0; a < 256; ++a) {
        const std::string_view hex = rows[a];
        for (std::size_t c = 0; c < 256; ++c) {
            t[(a * 256) + c] = static_cast<std::uint8_t>((nibble(hex[2 * c]) << 4U) | nibble(hex[(2 * c) + 1]));
        }
    }
    return t;
}

/// Java's Math.round(float): floor(a + 1/2) of the exact value, computed from the bits as the JDK does (NaN 0,
/// saturated beyond Int's limits).
[[nodiscard]] std::int32_t java_round(float a) noexcept {
    const auto bits = std::bit_cast<std::int32_t>(a);
    const std::int32_t biased = (bits & 0x7F800000) >> 23;
    const std::int32_t shift = (23 - 1 + 127) - biased;
    if ((shift & -32) == 0) {
        std::int32_t r = (bits & 0x007FFFFF) | 0x00800000;
        if (bits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    if (a != a) return 0;
    if (a >= 2147483648.0F) return 2147483647;
    if (a <= -2147483648.0F) return -2147483647 - 1;
    return static_cast<std::int32_t>(a);
}

/// vImage's rounding of an integer matrix row's sum: (sum + 128) >> 8, clamped to 0 to 255.
[[nodiscard]] std::uint32_t vimage(std::int32_t sum) noexcept {
    const std::int32_t v = (sum + 128) >> 8;
    return static_cast<std::uint32_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

} // namespace

std::span<const std::uint8_t, table_size> to_linear() noexcept {
    static const std::array<std::uint8_t, table_size> table = decode(linear_rows);
    return table;
}

std::span<const std::uint8_t, table_size> to_srgb() noexcept {
    static const std::array<std::uint8_t, table_size> table = decode(srgb_rows);
    return table;
}

Argb unpremultiply_exact(Argb p, bool to_linear_space) noexcept {
    const std::uint32_t a = p >> 24U;
    if (a == 0) return 0;
    const std::span<const std::uint8_t, table_size> t = to_linear_space ? to_linear() : to_srgb();
    const std::size_t row = static_cast<std::size_t>(a) << 8U;
    return pack(a, t[row + channel(p, 16)], t[row + channel(p, 8)], t[row + channel(p, 0)]);
}

Argb map_unpremultiplied(Argb p, bool to_linear_space) noexcept {
    const std::span<const std::uint8_t, table_size> t = to_linear_space ? to_linear() : to_srgb();
    constexpr std::size_t row = 255U << 8U;
    return (p & 0xFF000000U) | pack(0, t[row + channel(p, 16)], t[row + channel(p, 8)], t[row + channel(p, 0)]);
}

Argb srgb_to_linear_premultiplied(Argb p) noexcept {
    if ((p >> 24U) == 0) return 0;
    return premultiply(unpremultiply_exact(p, true));
}

std::uint32_t clamped(double v) noexcept {
    if (v != v || v <= 0) return 0;
    if (v >= 255) return 255;
    return static_cast<std::uint32_t>(pymath::round(v)); // Math.rint: ties to even
}

ColourMatrix::ColourMatrix(const std::array<float, 20>& m) noexcept
    : m_(m), integer_(m[4] == 0.0F && m[9] == 0.0F && m[14] == 0.0F && m[19] == 0.0F) {
    for (std::size_t k = 0; k < 16; ++k) q_[k] = java_round(m[((k % 4) * 5) + (k / 4)] * 256.0F);
}

std::uint32_t ColourMatrix::row(std::size_t k, float r, float g, float b, float a) const noexcept {
    float t = std::fma(m_[k], r, m_[k + 1] * g);
    t = std::fma(m_[k + 2], b, t);
    t = std::fma(m_[k + 3], a, t);
    t = std::fma(m_[k + 4], 255.0F, t);
    return clamped(static_cast<double>(t));
}

Argb ColourMatrix::apply(Argb p) const noexcept {
    const std::uint32_t r = channel(p, 16);
    const std::uint32_t g = channel(p, 8);
    const std::uint32_t b = channel(p, 0);
    const std::uint32_t a = p >> 24U;
    Argb u = 0;
    if (integer_) {
        const auto ri = static_cast<std::int32_t>(r);
        const auto gi = static_cast<std::int32_t>(g);
        const auto bi = static_cast<std::int32_t>(b);
        const auto ai = static_cast<std::int32_t>(a);
        const std::uint32_t orr = vimage((q_[0] * ri) + (q_[4] * gi) + (q_[8] * bi) + (q_[12] * ai));
        const std::uint32_t og = vimage((q_[1] * ri) + (q_[5] * gi) + (q_[9] * bi) + (q_[13] * ai));
        const std::uint32_t ob = vimage((q_[2] * ri) + (q_[6] * gi) + (q_[10] * bi) + (q_[14] * ai));
        const std::uint32_t oa = vimage((q_[3] * ri) + (q_[7] * gi) + (q_[11] * bi) + (q_[15] * ai));
        u = pack(oa, orr, og, ob);
    } else {
        const auto rf = static_cast<float>(r);
        const auto gf = static_cast<float>(g);
        const auto bf = static_cast<float>(b);
        const auto af = static_cast<float>(a);
        u = pack(row(15, rf, gf, bf, af), row(0, rf, gf, bf, af), row(5, rf, gf, bf, af), row(10, rf, gf, bf, af));
    }
    return premultiply(u);
}

} // namespace atlas::fx::bytes

namespace atlas::fx {

std::span<const std::uint8_t, bytes::table_size> crown_table() noexcept {
    static const std::array<std::uint8_t, bytes::table_size> table = [] {
        // the tone: crown_filter's feColorMatrix over the tone's noise, of its red alone, opaque
        const bytes::ColourMatrix tone({1.0F, 0.0F, 0.0F, 0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 0.0F, 0.5F,
                                        1.0F, 0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F});
        std::array<std::uint32_t, 256> t{};
        for (std::uint32_t red = 0; red < 256; ++red) {
            t[red] = bytes::channel(tone.apply(bytes::pack(255, red, red, red)), 16);
        }
        const std::span<const std::uint8_t, bytes::table_size> linear = bytes::to_linear();
        const std::span<const std::uint8_t, bytes::table_size> srgb = bytes::to_srgb();
        const float k1 = 1.0F / 255.0F;
        std::array<std::uint8_t, bytes::table_size> out{};
        for (std::uint32_t c = 0; c < 256; ++c) {
            const auto i1 = static_cast<float>(linear[(std::size_t{255} << 8U) | c]);
            for (std::uint32_t red = 0; red < 256; ++red) {
                // the arithmetic composite as the port computes it: k2 i1 + k3 i2 + (i1 · i2) · k1, truncated
                const float r = (i1 * static_cast<float>(t[red])) * k1;
                const std::uint32_t v = !(r > 0.0F) ? 0U : std::min<std::uint32_t>(static_cast<std::uint32_t>(r), 255U);
                out[(std::size_t{c} << 8U) | red] = srgb[(std::size_t{255} << 8U) | v];
            }
        }
        return out;
    }();
    return table;
}

std::vector<std::uint8_t> gpu_tables() {
    std::vector<std::uint8_t> t(tables_bytes);
    std::ranges::copy(bytes::to_linear(), t.begin() + table_to_linear);
    std::ranges::copy(bytes::to_srgb(), t.begin() + table_to_srgb);
    std::ranges::copy(mat_table(), t.begin() + table_mat);
    auto put = [&t](std::uint32_t at, std::span<const std::uint16_t> levels) {
        for (const std::uint16_t v : levels) {
            t[at] = static_cast<std::uint8_t>(v & 0xFFU);
            t[at + 1] = static_cast<std::uint8_t>(v >> 8U);
            at += 2;
        }
    };
    put(table_edge8, mat_edge_table(255));
    put(table_edge10, mat_edge_table(1023));
    std::ranges::copy(crown_table(), t.begin() + table_crown);
    return t;
}

} // namespace atlas::fx
