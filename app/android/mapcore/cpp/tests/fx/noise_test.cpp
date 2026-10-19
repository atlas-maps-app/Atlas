// WebKit's turbulence (fx/Noise.hpp) held to the port where no JVM runs (CI's arm64 runner, the sanitizer builds):
// the spec's check value, every generator's selector and gradients against fields.inc's hashes (the port's own,
// written by mapview's ByteTablesExportTest), the lanes against the scalar reference bit for bit over blocks of every
// shape, mask and kind, and libc++'s lerp and the smooth step on their edge cases.
#include "fx/Fields.hpp"
#include "fx/Noise.hpp"
#include "tests/base/fma_inputs.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {

using namespace atlas::fx;

/// One generator's line of fields.inc.
struct LatticeRow {
    int seed;
    std::uint64_t selector;
    std::uint64_t gradients;
};

// NOLINTBEGIN(cppcoreguidelines-macro-usage): fields.inc's lines, read as this table's rows
#define ATLAS_LATTICE(seed, selector, gradients) LatticeRow{seed, selector, gradients},
#define ATLAS_FIELD(size, width, height, scale, field, noise, kept)
constexpr std::array lattice_rows{
#include "fx/tables/fields.inc"
};
#undef ATLAS_FIELD
#undef ATLAS_LATTICE
// NOLINTEND(cppcoreguidelines-macro-usage)

template <class T>
std::vector<std::uint8_t> little_endian(std::span<const T> values) {
    std::vector<std::uint8_t> out;
    out.reserve(values.size() * 4);
    for (const T v : values) {
        const auto bits = std::bit_cast<std::uint32_t>(v);
        for (std::uint32_t b = 0; b < 4; ++b) out.push_back(static_cast<std::uint8_t>(bits >> (8U * b)));
    }
    return out;
}

} // namespace

TEST_CASE("the spec's Park–Miller generator gives its check value") {
    // The Filter Effects spec's comment: from seed 1, the 10,000th number is 1043618065.
    std::int64_t s = 1;
    for (int i = 0; i < 10'000; ++i) s = atlas::fx::random(s);
    CHECK(s == 1043618065);
}

TEST_CASE("every generator's selector and gradients are the port's") {
    REQUIRE(lattice_rows.size() >= 11);
    for (const LatticeRow& row : lattice_rows) {
        const Lattice l(static_cast<float>(row.seed));
        CAPTURE(row.seed);
        CHECK(hash_of(little_endian(l.selector())) == row.selector);
        CHECK(hash_of(little_endian(l.gradients())) == row.gradients);
        // by entry: the same floats, entry by entry
        bool same = true;
        for (std::size_t e = 0; e < 256; ++e) {
            for (std::size_t c = 0; c < 4; ++c) {
                same = same && l.by_entry()[(e * 8) + (2 * c)] == l.gradients()[((c * 256) + e) * 2] &&
                       l.by_entry()[(e * 8) + (2 * c) + 1] == l.gradients()[(((c * 256) + e) * 2) + 1];
            }
        }
        CHECK(same);
    }
}

TEST_CASE("a seed is truncated and clamped as the spec says") {
    // Seeds that truncate to the same integer make the same generator; zero and negatives fold into 1 … 2^31 − 2.
    const Lattice a(13.0F);
    const Lattice b(13.9F);
    CHECK(std::ranges::equal(a.gradients(), b.gradients()));
    const Lattice zero(0.0F);
    const Lattice one(1.0F);
    CHECK(std::ranges::equal(zero.selector(), one.selector())); // s ≤ 0: −(s mod (m − 1)) + 1, so 0 is 1
    const Lattice nan(std::numeric_limits<float>::quiet_NaN());
    CHECK(std::ranges::equal(nan.selector(), one.selector())); // NaN's (long) is 0
    const Lattice huge(1e30F);                                 // clamped to 2^31 − 2
    const Lattice top(2147483646.0F);
    CHECK(std::ranges::equal(huge.selector(), top.selector()));
}

TEST_CASE("libc++'s lerp and the smooth step on their edge cases") {
    // The straddling branch fuses t·b + (1 − t)·a; at t = 1 the end comes back exactly; past the ends the result is
    // kept monotonic.
    CHECK(lerp(-1.0F, 2.0F, 0.25F) == std::fma(0.25F, 2.0F, 0.75F * -1.0F));
    CHECK(lerp(3.0F, 7.0F, 1.0F) == 7.0F);
    CHECK(lerp(3.0F, 7.0F, 0.5F) == 5.0F);
    CHECK(lerp(7.0F, 3.0F, 0.5F) == 5.0F);
    CHECK(lerp(1.0F, 2.0F, 2.0F) == 3.0F);
    CHECK(smooth(0.0F) == 0.0F);
    CHECK(smooth(0.5F) == 0.5F);
    // the largest float below 1 rounds to 1: smooth can reach its end, so lerp's t == 1 branch is live
    CHECK(smooth(std::nextafter(1.0F, 0.0F)) == 1.0F);
}

TEST_CASE("the lanes equal the scalar reference bit for bit") {
    atlas::test::SplitMix64 rnd(20261019);
    // a float in [lo, hi), and an integer in [lo, hi]
    auto real = [&rnd](float lo, float hi) {
        return lo + ((hi - lo) * (static_cast<float>(rnd.next() >> 40U) * 0x1p-24F));
    };
    auto whole = [&rnd](int lo, int hi) {
        return lo + static_cast<int>(rnd.below(static_cast<std::uint32_t>(hi - lo + 1)));
    };
    const std::array<float, 6> seeds{13.0F, 8.0F, -7.0F, 0.0F, 3e9F, 23.0F};
    std::size_t pixels = 0;
    std::size_t differ = 0;
    for (std::size_t k = 0; k < 600; ++k) {
        const Lattice l(seeds[k % seeds.size()]);
        const Turbulence t{.seed = 0.0F,
                           .base_x = k % 17 == 0 ? 0.0F : real(0.0F, 1.2F),
                           .base_y = real(0.0F, 1.2F),
                           .octaves = whole(0, 5),
                           .fractal = k % 3 != 0};
        const Grid grid = grid_of(real(0.3F, 4.0F), real(0.3F, 4.0F));
        const Block block{
            .left = whole(-200, 3000), .top = whole(-200, 3000), .width = whole(1, 37), .height = whole(1, 9)};
        const auto need = static_cast<std::uint8_t>(k % 5 == 0 ? channels_all : whole(1, 15));
        const std::size_t stride = static_cast<std::size_t>(block.width) + 3;
        std::vector<Argb> scalar(stride * static_cast<std::size_t>(block.height), 0xDEADBEEF);
        std::vector<Argb> lanes(scalar.size(), 0xDEADBEEF);
        fill(l, t, grid, block, need, scalar, stride);
        fill_lanes(l, t, grid, block, need, lanes, stride);
        for (std::size_t i = 0; i < scalar.size(); ++i) {
            if (scalar[i] != lanes[i]) ++differ;
        }
        pixels += scalar.size();
    }
    CAPTURE(pixels);
    CHECK(differ == 0);
}

TEST_CASE("a block's pixels are the frame's pixels, wherever the block lies") {
    // A pixel depends only on its own coordinates: a block of a field equals the same pixels of a larger block.
    const Lattice l(13.0F);
    const Turbulence t = spec_of(Field::mat).noise;
    const Grid grid = grid_of(2.6213593F, 2.6213593F);
    const Block whole{.left = 0, .top = 0, .width = 97, .height = 23};
    std::vector<Argb> all(std::size_t{97} * 23);
    fill_lanes(l, t, grid, whole, channels_all, all, 97);
    const Block part{.left = 13, .top = 5, .width = 41, .height = 11};
    std::vector<Argb> some(std::size_t{41} * 11);
    fill_lanes(l, t, grid, part, channels_all, some, 41);
    bool same = true;
    for (std::size_t y = 0; y < 11; ++y) {
        for (std::size_t x = 0; x < 41; ++x) same = same && some[(y * 41) + x] == all[((y + 5) * 97) + x + 13];
    }
    CHECK(same);
    // and a channel left out is 0, alpha 255
    std::vector<Argb> red(std::size_t{97} * 23);
    fill_lanes(l, t, grid, whole, channel_red, red, 97);
    bool masked = true;
    for (std::size_t i = 0; i < red.size(); ++i) masked = masked && red[i] == (0xFF000000U | (all[i] & 0x00FF0000U));
    CHECK(masked);
}
