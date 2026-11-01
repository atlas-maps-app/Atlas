// The user's palette (the Map look's presets and colours; mapview's palette package): the colours the core draws in
// place of today's where the user changed them, by slot (PaletteCode's numbers), and the Map look's switches.
// Kotlin's state/PaletteBlock.kt writes it; read_palette_block refuses any other.
//
//   0   u32        version (1)
//   4   u32        flags (palette_flag)
//   8   u64        reserved (0)
//   16  u32 × 256  each slot's colour by its code: 0 where it is today's, else 0x01RRGGBB
//
// Today's look is the block of no colours and no flags: every pass then draws as it did before there were palettes.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string_view>

namespace atlas::style {

inline constexpr std::size_t palette_block_size = 1040;
inline constexpr std::uint32_t palette_block_version = 1;

/// The Map look's switches (PaletteFlags).
namespace palette_flag {
inline constexpr std::uint32_t no_guesses = 1U;    // only what OpenStreetMap maps: Atlas's guesses off
inline constexpr std::uint32_t grass = 2U;         // grass on bare ground instead of paper
inline constexpr std::uint32_t mapped_follow = 4U; // mapped colours move with the palette's nearest
inline constexpr std::uint32_t flat_greys = 8U;    // each guessed flat roof one of three mid greys, as alpha 22's
inline constexpr std::uint32_t known = 0xFU;
} // namespace palette_flag

/// The slots the passes draw by (PaletteCode's numbers: mapview's Slot).
namespace palette_slot {
inline constexpr std::size_t woods = 3;
inline constexpr std::size_t leaves = 112;
inline constexpr std::size_t evergreen = 113;
inline constexpr std::size_t bark = 114;
inline constexpr std::size_t benches = 115;
inline constexpr std::size_t lamp_posts = 116;
inline constexpr std::size_t bollards = 117;
inline constexpr std::size_t turbines = 118;
inline constexpr std::size_t pylons = 119;
} // namespace palette_slot

/// The woods' own colour (the Woods slot's today): the canopy's matrix's .16 .24 .12 in linear light as sRGB, which
/// the woods' generic trees' greens are darker tones of.
inline constexpr std::uint32_t woods_own = 0x6F8661U;

struct PaletteBlock {
    std::uint32_t version = palette_block_version;
    std::uint32_t flags = 0;
    std::uint64_t reserved = 0;
    std::array<std::uint32_t, 256> colours{};

    [[nodiscard]] bool has(std::uint32_t flag) const noexcept { return (flags & flag) != 0U; }

    /// The user's colour for the slot of `code` (0xRRGGBB), or none where it is today's.
    [[nodiscard]] std::optional<std::uint32_t> colour(std::size_t code) const noexcept {
        if (code >= colours.size() || colours.at(code) == 0U) return std::nullopt;
        return colours.at(code) & 0xFFFFFFU;
    }

    /// True for today's look: no colour changed and no switch set.
    [[nodiscard]] bool today() const noexcept;
};
static_assert(sizeof(PaletteBlock) == palette_block_size);

/// Why a palette block was refused.
enum class PaletteError : std::uint8_t {
    size,     // not 1,040 bytes
    version,  // another layout version
    flags,    // a switch version 1 does not know
    reserved, // the reserved field is not 0
    colour,   // a slot's entry is neither 0 nor 0x01RRGGBB
};

[[nodiscard]] std::string_view name_of(PaletteError error) noexcept;

/// The palette in `bytes` (little-endian, as Kotlin writes it), or why it is refused.
[[nodiscard]] std::expected<PaletteBlock, PaletteError> read_palette_block(std::span<const std::byte> bytes) noexcept;

/// `c` carried along as the colour it goes with, `from`, becomes `to` (0xRRGGBB each): the palette's rule for a
/// slot's night, its lit side, its autumn and the parts drawn with it (mapview's PaletteTheme.move). In Oklab, `c`
/// keeps its lightness against `from`'s (a darker tone the same share of the way to black, a lighter one of the way to
/// white) and its tint against `from`'s, per unit of lightness, turned as `from`'s turns into `to`'s and lessened as
/// `to`'s is greyer, never strengthened. So `from` becomes `to`, a darker tone of it the same tone of `to`, and a grey
/// `to` greys whatever goes with it.
[[nodiscard]] std::uint32_t palette_move(std::uint32_t c, std::uint32_t from, std::uint32_t to) noexcept;

/// A style's colour `c` for the slot of `code`, whose own colour in the style is `own` (0xRRGGBB each), as `palette`
/// has it (null: today's): `c` while the slot is today's, else `c` moved as `own` becomes the slot's colour.
[[nodiscard]] std::uint32_t paint(const PaletteBlock* palette, std::size_t code, std::uint32_t own,
                                  std::uint32_t c) noexcept;

} // namespace atlas::style
