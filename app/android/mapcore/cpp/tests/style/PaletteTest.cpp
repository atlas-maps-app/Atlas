#include "style/Palette.hpp"

#include "base/pymath/PyMath.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <initializer_list>

namespace {

using atlas::style::PaletteBlock;
using atlas::style::PaletteError;

std::array<std::byte, atlas::style::palette_block_size> bytes(const PaletteBlock& b) {
    std::array<std::byte, atlas::style::palette_block_size> out{};
    std::memcpy(out.data(), &b, sizeof b);
    return out;
}

[[nodiscard]] int channel(std::uint32_t c, unsigned shift) { return static_cast<int>((c >> shift) & 0xFFU); }

/// The largest difference of two colours' channels.
[[nodiscard]] int apart(std::uint32_t x, std::uint32_t y) {
    int most = 0;
    for (const unsigned sh : {16U, 8U, 0U}) most = std::max(most, std::abs(channel(x, sh) - channel(y, sh)));
    return most;
}

/// The colour with each channel's linear light `k` times `c`'s.
[[nodiscard]] std::uint32_t lit(std::uint32_t c, double k) {
    std::uint32_t out = 0;
    for (const unsigned sh : {16U, 8U, 0U}) {
        const double v = channel(c, sh) / 255.0;
        const double l = k * (v <= 0.04045 ? v / 12.92 : atlas::pymath::strict::pow((v + 0.055) / 1.055, 2.4));
        const double e = l <= 0.0031308 ? 12.92 * l : (1.055 * atlas::pymath::strict::pow(l, 1.0 / 2.4)) - 0.055;
        out |= static_cast<std::uint32_t>(std::lround(e * 255.0)) << sh;
    }
    return out;
}

} // namespace

TEST_CASE("a palette block reads back as written, today's look as no colours and no switches") {
    PaletteBlock b{};
    CHECK(b.today());
    b.colours.at(80) = 0x019E5038U; // the red tiles, alpha 22's
    b.flags = atlas::style::palette_flag::flat_greys;
    const auto raw = bytes(b);
    const auto read = atlas::style::read_palette_block(raw);
    REQUIRE(read.has_value());
    CHECK_FALSE(read->today());
    CHECK(read->colour(80) == 0x9E5038U);
    CHECK_FALSE(read->colour(81).has_value());
    CHECK_FALSE(read->colour(999).has_value());
    CHECK(read->has(atlas::style::palette_flag::flat_greys));
}

TEST_CASE("a palette block is refused as anything else") {
    const PaletteBlock good{};
    const auto refused = [](const PaletteBlock& b) {
        const auto raw = bytes(b);
        return atlas::style::read_palette_block(raw).error();
    };
    PaletteBlock v = good;
    v.version = 2;
    CHECK(refused(v) == PaletteError::version);
    PaletteBlock f = good;
    f.flags = 16U;
    CHECK(refused(f) == PaletteError::flags);
    PaletteBlock r = good;
    r.reserved = 1U;
    CHECK(refused(r) == PaletteError::reserved);
    PaletteBlock c = good;
    c.colours.at(1) = 0x02D6CFC2U;
    CHECK(refused(c) == PaletteError::colour);
    const auto raw = bytes(good);
    CHECK(atlas::style::read_palette_block(std::span(raw).first(1039)).error() == PaletteError::size);
    CHECK(atlas::style::name_of(PaletteError::colour) == "colour");
}

TEST_CASE("a colour moves with the one it goes with: that one exactly, a tone of it in kind, a grey greys it") {
    using atlas::style::palette_move;
    constexpr std::uint32_t bark = 0x4B4038U; // the style's bark by day
    constexpr std::uint32_t red = 0x803028U;
    CHECK(palette_move(bark, bark, red) == red);
    // the same colour moves nothing
    for (const std::uint32_t c : {0x0B0D10U, 0x8A7B6CU, 0x4A3A2EU, 0x9C8A36U, 0xE6ECF2U, 0xFFFFFFU, 0U}) {
        CAPTURE(c);
        CHECK(apart(palette_move(c, bark, bark), c) <= 1);
    }
    // a tone of it under less light becomes the same tone of the new colour; under more, a lighter one of its hue
    for (const double k : {0.1, 0.3, 0.7}) {
        CAPTURE(k);
        CHECK(apart(palette_move(lit(bark, k), bark, red), lit(red, k)) <= 2);
    }
    const std::uint32_t lighter = palette_move(lit(bark, 1.6), bark, red);
    CHECK(channel(lighter, 16) > channel(red, 16));
    CHECK(channel(lighter, 16) > channel(lighter, 8) + 40);
    CHECK(palette_move(0U, bark, red) == 0U);
    // a grey greys the lit side, the night and an autumn's yellow (each a grey within a step), darker or lighter
    constexpr std::uint32_t grey = 0x707070U;
    for (const std::uint32_t c : {0x8A7B6CU, 0x0B0D10U, 0x9C8A36U}) {
        CAPTURE(c);
        const std::uint32_t g = palette_move(c, bark, grey);
        CHECK(apart(g, (g & 0xFFU) * 0x010101U) <= 2);
    }
    CHECK(channel(palette_move(0x8A7B6CU, bark, grey), 0) > 0x70);
    CHECK(channel(palette_move(0x0B0D10U, bark, grey), 0) < 0x30);
    // a grey's tones take the new colour's tint: a lamp's lantern goes red with its mast
    const std::uint32_t lantern = palette_move(0x3B4148U, 0x2A2E33U, red);
    CHECK(channel(lantern, 16) > channel(lantern, 8) + 20);
}

TEST_CASE("a building's colour goes with the nearest wall or roof slot, its own exactly") {
    using atlas::style::building_today;
    using atlas::style::nearest_slot;
    using atlas::style::roof_slots;
    using atlas::style::wall_slots;
    // each slot's own colour goes with it, the codes ascending in their families
    for (const auto& s : wall_slots) CHECK(nearest_slot(wall_slots, s.today) == s.code);
    for (const auto& s : roof_slots) CHECK(nearest_slot(roof_slots, s.today) == s.code);
    CHECK(std::ranges::is_sorted(wall_slots, {}, &atlas::style::SlotColour::code));
    CHECK(std::ranges::is_sorted(roof_slots, {}, &atlas::style::SlotColour::code));
    // the generic looks' brick goes with the old brick nearest it, a concrete with the concrete
    CHECK(nearest_slot(wall_slots, 0x8E5A46U) == 51U);
    CHECK(nearest_slot(wall_slots, 0xBDBAB2U) == 63U);
    CHECK(nearest_slot(roof_slots, 0x9E5038U) == 80U);
    CHECK(building_today(53) == 0x7E5242U);
    CHECK(building_today(87) == 0x56585CU);
    CHECK(building_today(19) == 0U);
}
