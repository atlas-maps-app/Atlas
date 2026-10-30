// The regions' canopy's arithmetic (fx/Canopy.hpp) held to SvgFilterRaster's: the matrix's alpha for each red and the
// filter's result over a pixel the path covers for each alpha, against canopy.inc's hashes (mapview's
// ByteTablesExportTest, which first holds its transcription to SvgFilterRaster's own canopy over a frame of its noise);
// the field keeps that alpha of the noise's red; and the filter in its limits: whole where the red is low, nothing
// where it is high, a mid green between.
#include "fx/ByteTables.hpp"
#include "fx/Canopy.hpp"
#include "fx/Fields.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <vector>

namespace {

using namespace atlas::fx;

/// canopy.inc's two hashes.
struct CanopyHashes {
    std::uint64_t alpha;
    std::uint64_t table;
};

// NOLINTBEGIN(cppcoreguidelines-macro-usage): canopy.inc's line
#define ATLAS_CANOPY(alpha, table)                                                                                     \
    CanopyHashes { alpha, table }
constexpr CanopyHashes canopy_expected =
#include "fx/tables/canopy.inc"
    ;
#undef ATLAS_CANOPY
// NOLINTEND(cppcoreguidelines-macro-usage)

} // namespace

TEST_CASE("fx: the canopy's matrix and its filter's result are SvgFilterRaster's") {
    CHECK(hash_of(canopy_alpha()) == canopy_expected.alpha);
    std::vector<std::uint8_t> table;
    for (const Argb p : canopy_table()) {
        for (std::uint32_t b = 0; b < 4; ++b) table.push_back(static_cast<std::uint8_t>(p >> (8U * b)));
    }
    CHECK(hash_of(table) == canopy_expected.table);
}

TEST_CASE("fx: the canopy's field keeps its matrix's alpha of the red the matrix reads") {
    const Block block{.left = 300, .top = 700, .width = 64, .height = 64};
    const auto scale = static_cast<float>(1080.0 / 412.0);
    const std::shared_ptr<const ScreenFields> fields = make_block(block, scale);
    const FieldSpec& spec = spec_of(Field::canopy);
    std::vector<Argb> noise(std::size_t{64} * 64);
    fill(Lattice(spec.noise.seed), spec.noise, grid_of(scale, scale), block, channels_all, noise, 64);
    std::size_t same = 0;
    std::array<bool, 256> seen{};
    for (std::uint32_t y = 0; y < 64; ++y) {
        for (std::uint32_t x = 0; x < 64; ++x) {
            const Argb p = noise[(y * 64) + x];
            const std::uint8_t want = canopy_alpha()[bytes::channel(bytes::unpremultiply(bytes::premultiply(p)), 16)];
            const std::uint8_t got = fields->at(Field::canopy, x, y)[0];
            if (got == want) ++same;
            seen[got] = true;
        }
    }
    CHECK(same == 64U * 64U);
    // the noise reaches both ends: bare land and a whole canopy
    CHECK(seen[0]);
    CHECK(seen[255]);
}

TEST_CASE("fx: the canopy in its limits") {
    const std::array<std::uint8_t, 256>& alpha = canopy_alpha();
    // 535.5 − 3.4 red: whole up to red 82, nothing from red 158
    CHECK(alpha[0] == 255);
    CHECK(alpha[82] == 255);
    CHECK(alpha[83] == 253);
    CHECK(alpha[157] == 2);
    CHECK(alpha[158] == 0);
    CHECK(alpha[255] == 0);
    // the matrix's colour, .16 .24 .12 in linear light: a mid green once written as sRGB, premultiplied by its alpha
    const std::array<Argb, 256>& table = canopy_table();
    CHECK(table[0] == 0U);
    const Argb whole = table[255];
    CHECK((whole >> 24U) == 255U);
    const std::uint32_t r = bytes::channel(whole, 16);
    const std::uint32_t g = bytes::channel(whole, 8);
    const std::uint32_t b = bytes::channel(whole, 0);
    CHECK(g > r);
    CHECK(r > b);
    CHECK(r >= 108);
    CHECK(r <= 116);
    for (std::uint32_t a = 1; a < 256; ++a) {
        CHECK((table[a] >> 24U) == a);
        CHECK(bytes::channel(table[a], 8) <= a);
    }
}
