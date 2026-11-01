#include "style/Palette.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstring>

namespace {

using atlas::style::PaletteBlock;
using atlas::style::PaletteError;

std::array<std::byte, atlas::style::palette_block_size> bytes(const PaletteBlock& b) {
    std::array<std::byte, atlas::style::palette_block_size> out{};
    std::memcpy(out.data(), &b, sizeof b);
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
