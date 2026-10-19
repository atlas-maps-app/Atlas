// `mat`'s byte arithmetic (fx/Mat.hpp) held to SvgFilterRaster's: its result on every source pixel (a, c, c, c) and
// grey, against mat.inc's hash (mapview's ByteTablesExportTest, which first holds its transcription to
// SvgFilterRaster's own mat filter over half a million pixels); the opaque table the GPU reads; and the filter's two
// effects in their limits: an opaque colour scaled in linear light by 0.9 + 0.2 g, a partly covered pixel's alpha
// thinned to min(1, 1.1 A) · A.
#include "fx/ByteTables.hpp"
#include "fx/Fields.hpp"
#include "fx/Mat.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

namespace {

using namespace atlas::fx;

// NOLINTBEGIN(cppcoreguidelines-macro-usage): mat.inc's line
#define ATLAS_MAT_PIXEL(hash) hash
constexpr std::uint64_t mat_pixel_expected =
#include "fx/tables/mat.inc"
    ;
#undef ATLAS_MAT_PIXEL
// NOLINTEND(cppcoreguidelines-macro-usage)

} // namespace

TEST_CASE("mat's filter is SvgFilterRaster's on every source pixel and grey") {
    std::vector<std::uint8_t> all(256 + (256 * 256 * 256));
    for (std::uint32_t a = 0; a < 256; ++a) all[a] = static_cast<std::uint8_t>(mat_pixel(a << 24U, 0) >> 24U);
    for (std::uint32_t a = 0; a < 256; ++a) {
        for (std::uint32_t c = 0; c < 256; ++c) {
            const Argb p = bytes::pack(a, c, c, c);
            for (std::uint32_t g = 0; g < 256; ++g) {
                all[256 + (((a * 256) + c) * 256) + g] = static_cast<std::uint8_t>(bytes::channel(mat_pixel(p, g), 16));
            }
        }
    }
    CHECK(hash_of(all) == mat_pixel_expected);
}

TEST_CASE("the opaque table is mat's filter on opaque pixels") {
    const auto table = mat_table();
    bool same = true;
    for (std::uint32_t c = 0; c < 256; ++c) {
        for (std::uint32_t g = 0; g < 256; ++g) {
            same = same && table[(c * 256) + g] == bytes::channel(mat_pixel(bytes::pack(255, c, c, c), g), 16) &&
                   table[(c * 256) + g] == mat_opaque(c, g);
        }
    }
    CHECK(same);
}

TEST_CASE("mat in its limits: linear light scaled by 0.9 + 0.2 g, the silhouettes thinned") {
    // An opaque white at grey 0 is scaled by 0.9 in linear light, at grey 255 by 1.1 (clamped): darker, and white.
    CHECK(mat_opaque(255, 0) < 255);
    CHECK(mat_opaque(255, 255) == 255);
    CHECK(mat_opaque(0, 128) == 0);
    // The colour rises with the grey.
    bool rising = true;
    for (std::uint32_t g = 1; g < 256; ++g) rising = rising && mat_opaque(180, g) >= mat_opaque(180, g - 1);
    CHECK(rising);
    // A pixel covered a quarter keeps about 1.1 · 0.25² of its alpha; a whole one keeps all of it.
    const std::uint32_t quarter = mat_pixel(bytes::pack(64, 40, 40, 40), 128) >> 24U;
    CHECK(quarter == 18); // trunc(64 · 0.9 + 64 · 255 · k1) = 70, then 70 · 64 / 255 rounded
    const std::uint32_t whole = mat_pixel(bytes::pack(255, 40, 40, 40), 128) >> 24U;
    CHECK(whole == 255);
    CHECK(mat_pixel(0, 200) == 0);
}
