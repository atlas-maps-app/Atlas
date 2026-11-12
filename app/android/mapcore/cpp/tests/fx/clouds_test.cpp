// The sky layer's clouds' arithmetic (fx/Clouds.hpp) held to SvgFilterRaster's: for a clear day, an overcast one and
// a night, at filter scales whose offset of 5 px is whole rows and at ones where it is not, cloudsh2's result and
// cloudf's over the sky's rows (the clouds' field's bytes, the offset's blend, each matrix's table) against
// clouds.inc's hashes (mapview's ByteTablesExportTest, which first holds its transcription to SvgFilterRaster's own
// two filters on every pixel); and the pieces in their limits: the matrices as the render prints them, the offset's
// rows and weight, the region's rows.
#include "fx/ByteTables.hpp"
#include "fx/Clouds.hpp"
#include "fx/Fields.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace {

using namespace atlas::fx;

/// A line of clouds.inc: the clouds (their colour, alpha k (n − t)), the filter scale's bits, the pixels of a row and
/// the rows, and the two results' hashes.
struct CloudCase {
    std::string_view name;
    double red;
    double green;
    double blue;
    double k;
    double t;
    std::uint32_t scale_bits;
    std::uint32_t width;
    std::uint32_t rows;
    std::uint64_t shadow;
    std::uint64_t cloud;
};

// NOLINTBEGIN(cppcoreguidelines-macro-usage): clouds.inc's lines, read as this table's rows
#define ATLAS_CLOUDS(name, red, green, blue, k, t, scale, width, rows, shadow, cloud)                                  \
    CloudCase{name, red, green, blue, k, t, scale, width, rows, shadow, cloud},
constexpr std::array cloud_cases{
#include "fx/tables/clouds.inc"
};
#undef ATLAS_CLOUDS
// NOLINTEND(cppcoreguidelines-macro-usage)

void put(std::vector<std::uint8_t>& out, Argb p) {
    for (std::uint32_t b = 0; b < 4; ++b) out.push_back(static_cast<std::uint8_t>(p >> (8U * b)));
}

} // namespace

TEST_CASE("fx: the clouds' two filters' results over the sky's rows are SvgFilterRaster's") {
    CHECK(cloud_cases.size() == 15U);
    for (const CloudCase& c : cloud_cases) {
        const auto scale = std::bit_cast<float>(c.scale_bits);
        CAPTURE(std::string(c.name));
        CAPTURE(scale);
        // the filters' region is 120 rows of CSS px from the origin
        CHECK(cloud_rows(120.9, scale, 100'000U) == c.rows);
        const std::shared_ptr<const ScreenFields> fields = make_block(
            {.left = 0,
             .top = 0,
             .width = static_cast<std::int32_t>(c.width),
             .height = static_cast<std::int32_t>(c.rows)},
            scale);
        const std::array<Argb, 256> shadow_table = cloud_table(cloud_shadow_matrix(c.red, c.green, c.blue, c.k, c.t));
        const std::array<Argb, 256> table = cloud_table(cloud_matrix(c.red, c.green, c.blue, c.k, c.t));
        const CloudOffset offset = cloud_offset(scale);
        std::vector<std::uint8_t> shadow;
        std::vector<std::uint8_t> cloud;
        shadow.reserve(std::size_t{c.width} * c.rows * 4);
        cloud.reserve(std::size_t{c.width} * c.rows * 4);
        for (std::uint32_t y = 0; y < c.rows; ++y) {
            for (std::uint32_t x = 0; x < c.width; ++x) {
                const CloudReds reds = cloud_reds(*fields, offset, x, y);
                put(shadow, shadow_table[reds.shadow]);
                put(cloud, table[reds.cloud]);
            }
        }
        CHECK(hash_of(shadow) == c.shadow);
        CHECK(hash_of(cloud) == c.cloud);
    }
}

TEST_CASE("fx: the clouds' matrices are the numbers sky_layer prints") {
    // a clear day with half the sky covered: '%.2f' of -9 · .495 and of -9 · .535
    const CloudMatrix day = cloud_matrix(0.98, 0.98, 0.99, 9.0, 0.62 - (0.5 * 0.25));
    CHECK(day == CloudMatrix{.red = 0.98F, .green = 0.98F, .blue = 0.99F, .k = 9.0F, .offset = -4.46F});
    const CloudMatrix day_shadow = cloud_shadow_matrix(0.98, 0.98, 0.99, 9.0, 0.62 - (0.5 * 0.25));
    CHECK(day_shadow == CloudMatrix{.red = 0.61F, .green = 0.63F, .blue = 0.69F, .k = 9.0F, .offset = -4.82F});
    const CloudMatrix grey = cloud_matrix(0.66, 0.68, 0.72, 2.2, 0.18);
    CHECK(grey == CloudMatrix{.red = 0.66F, .green = 0.68F, .blue = 0.72F, .k = 2.2F, .offset = -0.40F});
    const CloudMatrix grey_shadow = cloud_shadow_matrix(0.66, 0.68, 0.72, 2.2, 0.18);
    CHECK(grey_shadow == CloudMatrix{.red = 0.41F, .green = 0.44F, .blue = 0.50F, .k = 2.2F, .offset = -0.48F});
    const CloudMatrix night = cloud_matrix(0.17, 0.15, 0.19, 1.5, -0.1);
    CHECK(night == CloudMatrix{.red = 0.17F, .green = 0.15F, .blue = 0.19F, .k = 1.5F, .offset = 0.15F});
    const CloudMatrix night_shadow = cloud_shadow_matrix(0.17, 0.15, 0.19, 1.5, -0.1);
    CHECK(night_shadow == CloudMatrix{.red = 0.11F, .green = 0.10F, .blue = 0.13F, .k = 1.5F, .offset = 0.09F});
    // by day the clear sky shows nothing and a cloud's heart is whole; at night the low cloud covers all the sky
    const std::array<Argb, 256> clear = cloud_table(day);
    CHECK(clear[0] == 0U);
    CHECK(clear[126] == 0U); // 9 · 126 − 4.46 · 255 < 0.5
    CHECK((clear[127] >> 24U) == 6U);
    CHECK((clear[255] >> 24U) == 255U);
    CHECK(bytes::channel(clear[255], 0) >= bytes::channel(clear[255], 16));
    const std::array<Argb, 256> low = cloud_table(night);
    CHECK((low[0] >> 24U) == 38U); // .15 · 255
    CHECK((low[255] >> 24U) == 255U);
}

TEST_CASE("fx: cloudsh2's offset is 5 px at the filter's scale, whole rows and the fraction left") {
    CHECK(cloud_offset(1.0F) == CloudOffset{.rows = 5, .above = 0U});
    CHECK(cloud_offset(2.0F) == CloudOffset{.rows = 10, .above = 0U});
    CHECK(cloud_offset(2.625F) == CloudOffset{.rows = 13, .above = cloud_weight_one / 8U});
    CHECK(cloud_offset(3.5F) == CloudOffset{.rows = 17, .above = cloud_weight_one / 2U});
    const CloudOffset phone = cloud_offset(static_cast<float>(1080.0 / 412.0));
    CHECK(phone.rows == 13);
    CHECK(phone.above > 0U);
    CHECK(phone.above < cloud_weight_one / 8U);
    // the blend: the pixel itself at a whole number of rows, else between the two, rounded half up
    CHECK(cloud_blend(200U, 100U, 0U) == 200U);
    CHECK(cloud_blend(200U, 100U, cloud_weight_one / 2U) == 150U);
    CHECK(cloud_blend(255U, 255U, cloud_weight_one - 1U) == 255U);
    CHECK(cloud_blend(1U, 0U, cloud_weight_one / 2U) == 1U);
    CHECK(cloud_blend(0U, 0U, cloud_weight_one / 8U) == 0U);
    // the red a matrix reads of a premultiplied pixel
    CHECK(cloud_red(0U, 0U) == 0U);
    CHECK(cloud_red(77U, 255U) == 77U);
    CHECK(cloud_red(50U, 100U) == 128U); // (50 · 255 + 50) / 100
    CHECK(cloud_red(100U, 100U) == 255U);
    // the region: W × int(hb) user px from the origin, in whole filter pixels, within the frame
    CHECK(cloud_rows(233.7, 1.0F, 2400U) == 233U);
    CHECK(cloud_rows(233.7, 2.625F, 2400U) == 612U); // ceil(233 · 2.625 = 611.625)
    CHECK(cloud_rows(2000.0, 2.625F, 2400U) == 2400U);
    CHECK(cloud_rows(0.4, 2.0F, 2400U) == 0U);
}
